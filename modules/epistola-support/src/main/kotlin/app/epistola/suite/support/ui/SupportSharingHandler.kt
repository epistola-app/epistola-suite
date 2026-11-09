// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePlatformRole
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.sharing.GetSharingRestrictions
import app.epistola.suite.support.sharing.InstallationSharingView
import app.epistola.suite.support.sharing.NOT_ALLOWED
import app.epistola.suite.support.sharing.REQUIRED
import app.epistola.suite.support.sharing.SetSharingRestriction
import app.epistola.suite.support.sharing.SharingRestriction
import app.epistola.suite.support.sharing.SharingRestrictions
import app.epistola.suite.support.sharing.SharingRestrictionsResult
import app.epistola.suite.support.sharing.TenantSharingSection
import app.epistola.suite.support.sharing.TenantSharingView
import app.epistola.suite.support.sharing.field
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import java.time.OffsetDateTime
import java.util.UUID

/** Where a tenant stands with what it shares with Epistola. */
enum class TenantSharingState {
    /** No choice of its own and no installation preset: the tenant still has to set it up. */
    NOT_SET_UP,

    /** No choice of its own: it follows the installation preset. */
    PRESET,

    /** It chose itself. */
    OWN,
}

/**
 * What a tenant shares with Epistola, for its Support page.
 *
 * @property canManage the viewer may change it ([Permission.INTEGRATIONS_MANAGE]).
 */
data class TenantSharingOverview(
    val tenantKey: TenantKey,
    val tenantName: String,
    val views: List<TenantSharingView>,
    val preset: List<InstallationSharingView>,
    val canManage: Boolean,
) {
    /** An installation preset exists: from an installation administrator or the configuration. */
    val presetSet: Boolean get() = preset.any { it.isSet }

    val state: TenantSharingState
        get() = when {
            views.any { !it.followsPreset } -> TenantSharingState.OWN
            presetSet -> TenantSharingState.PRESET
            else -> TenantSharingState.NOT_SET_UP
        }

    private val latestPreset: InstallationSharingView? get() = preset.filter { it.isSet }.maxByOrNull { it.setAt!! }

    /** The preset came from the deployment configuration, not from a person. */
    val presetFromConfiguration: Boolean get() = latestPreset?.fromConfiguration == true
    val presetBy: String? get() = latestPreset?.setBy
    val presetAt: OffsetDateTime? get() = latestPreset?.setAt

    private val latestOwn: TenantSharingView? get() = views.filter { !it.followsPreset && it.decidedAt != null }.maxByOrNull { it.decidedAt!! }
    val decidedBy: String? get() = latestOwn?.decidedBy
    val decidedAt: OffsetDateTime? get() = latestOwn?.decidedAt

    /**
     * There is nothing for this tenant to choose: every service is Required, or the installation uses
     * none that tenants share. The page says so, rather than open a wizard with no steps.
     */
    val nothingToChoose: Boolean get() = views.isEmpty()

    /** Setting it up is offered: nothing is set up yet, there is something to choose, and the viewer may do it. */
    val offersSetup: Boolean get() = state == TenantSharingState.NOT_SET_UP && canManage && !nothingToChoose

    /** Going back to the preset is offered: the tenant chose itself, a preset exists, and the viewer may change it. */
    val offersUsePreset: Boolean get() = state == TenantSharingState.OWN && presetSet && canManage
}

/**
 * What a tenant shares with one support connection other than Epistola support (ADR 0053), for its
 * Support page: the sections that connection offers, each with the tenant's choice in force.
 *
 * @property canManage the viewer may change it ([Permission.INTEGRATIONS_MANAGE]).
 */
data class TenantConnectionSharing(
    val connectionId: UUID,
    val name: String,
    val views: List<TenantSharingView>,
    val canManage: Boolean,
) {
    /** The tenant chose itself for at least one section; it can go back to the installation's setup. */
    val ownChoice: Boolean get() = views.any { !it.followsPreset }
}

/** The installation's preset for one support connection other than Epistola support, for `/platform/support`. */
data class ConnectionSharingPreset(
    val connectionId: UUID,
    val name: String,
    val views: List<InstallationSharingView>,
)

/**
 * The setup wizard: an introduction, one step per [TenantSharingSection], and a review with the
 * agreement. The choices travel along as hidden fields; nothing is saved before "Finish setup".
 *
 * @property step 0 the introduction, 1..[sections] size one per section, then the review.
 * @property values the choices so far, by section key.
 * @property changing the tenant was set up already (by itself or the preset), so this changes it.
 */
data class TenantSharingWizard(
    val tenantKey: TenantKey,
    val tenantName: String,
    val sections: List<TenantSharingSection>,
    val views: List<TenantSharingView>,
    val step: Int,
    val values: Map<String, String>,
    val changing: Boolean,
    val error: String? = null,
) {
    val titles: List<String> get() = listOf("About") + sections.map { it.title } + "Agree"
    val lastStep: Int get() = sections.size + 1
    val isIntro: Boolean get() = step == 0
    val isReview: Boolean get() = step == lastStep

    /** The section of this step; null on the introduction and the review. */
    val section: TenantSharingSection? get() = sections.getOrNull(step - 1)

    /** The view of this step's section: the reasons a choice is withheld. */
    val view: TenantSharingView? get() = section?.let { s -> views.firstOrNull { it.section.key == s.key } }

    /** The label of the choice made for [section]. */
    fun labelOf(section: TenantSharingSection): String = section.choices.firstOrNull { it.value == values[section.key] }?.label ?: "Not chosen"
}

/**
 * The one place a tenant sets what it shares with Epistola, on its Support page: a setup wizard
 * while nothing is set up, the installation preset while it follows one, its own choice otherwise,
 * and the installation preset on `/platform/support`. Each section applies its own choice through its
 * module's commands, which enforce who may do what.
 */
@Component
class SupportSharingHandler(
    sections: List<TenantSharingSection>,
    private val registry: SupportConnectionRegistry,
    private val connections: SupportConnections,
) {
    private val sections = sections.sortedBy { it.order }

    /** What the tenant shares; the views need [Permission.INTEGRATIONS_MANAGE] to read the consent, so others get null. */
    fun overview(tenantKey: TenantKey): TenantSharingOverview? {
        if (!canManage(tenantKey)) return null
        val tenantName = GetTenant(tenantKey).query()?.name ?: tenantKey.value
        val restrictions = GetSharingRestrictions().query()
        // A Required section is no tenant's choice: its Support page does not show it (ADR 0052 §5). Nor
        // is a service the installation does not use.
        val views = sections.filter { restrictions.of(it.key) != SharingRestriction.REQUIRED && it.offered() }.map { section ->
            val view = section.tenantView(tenantKey)
            if (restrictions.of(section.key) != SharingRestriction.NOT_ALLOWED) {
                view
            } else {
                view.copy(withheld = view.withheld + section.choices.filter { it.shares }.associate { it.value to SharingRestrictions.NOT_ALLOWED_REASON })
            }
        }
        return TenantSharingOverview(tenantKey, tenantName, views, installationViews(restrictions), canManage = true)
    }

    /** Every section's installation preset and restriction, for `/platform/support`. */
    fun installationViews(restrictions: SharingRestrictionsResult = GetSharingRestrictions().query()): List<InstallationSharingView> = sections.map { it.installationView().copy(restriction = restrictions.of(it.key)) }

    /**
     * What [tenantKey] shares with each support connection other than Epistola support that offers
     * anything; empty when none does, or when the viewer may not manage it.
     */
    fun connectionSharing(tenantKey: TenantKey): List<TenantConnectionSharing> {
        if (!canManage(tenantKey)) return emptyList()
        return registry.tokenConnections().mapNotNull { connection ->
            val restrictions = GetSharingRestrictions(connection.id).query()
            val views = sections.filter { restrictions.of(it.key) != SharingRestriction.REQUIRED && it.offered(connection.id) }.map { section ->
                val view = section.tenantView(tenantKey, connection.id)
                if (restrictions.of(section.key) != SharingRestriction.NOT_ALLOWED) {
                    view
                } else {
                    view.copy(withheld = view.withheld + section.choices.filter { it.shares }.associate { it.value to SharingRestrictions.NOT_ALLOWED_REASON })
                }
            }
            views.takeIf { it.isNotEmpty() }?.let { TenantConnectionSharing(connection.id, nameOf(connection.id), it, canManage = true) }
        }
    }

    /** The installation's preset for each support connection other than Epistola support, for `/platform/support`. */
    fun connectionPresets(): List<ConnectionSharingPreset> = registry.tokenConnections().mapNotNull { connection ->
        val restrictions = GetSharingRestrictions(connection.id).query()
        val views = sections.filter { it.offered(connection.id) }.map { it.installationView(connection.id).copy(restriction = restrictions.of(it.key)) }
        views.takeIf { it.isNotEmpty() }?.let { ConnectionSharingPreset(connection.id, nameOf(connection.id), it) }
    }

    private fun nameOf(connectionId: UUID): String = connections.find(connectionId)?.name ?: connectionId.toString()

    /**
     * Saves what the tenant shares with one support connection, as its own choice. Sharing anything is
     * agreed on the tenant's behalf (`agree=true`), as in the setup; a choice withheld now is refused.
     */
    fun saveConnectionSharing(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        val connectionId = connectionIdOf(request) ?: return ServerResponse.badRequest().build()
        val sharing = connectionSharing(tenantKey).firstOrNull { it.connectionId == connectionId } ?: return sharingPanel(request, null)
        val form = request.params()
        val chosen = sharing.views.mapNotNull { v -> form.field("${v.section.key}.choice")?.takeIf { c -> v.section.choices.any { it.value == c } }?.let { v to it } }
        chosen.firstNotNullOfOrNull { (v, c) -> v.withheld[c] }?.let { return sharingPanel(request, null, connectionError = connectionId to it) }
        val shares = chosen.any { (v, c) -> v.section.choices.any { it.value == c && it.shares } }
        if (shares && form.field("agree") != "true") {
            return sharingPanel(request, null, connectionError = connectionId to "Tick that you agree on behalf of the tenant to share with ${sharing.name}.")
        }
        chosen.forEach { (v, c) -> v.section.applyOwn(tenantKey, c, connectionId) }
        return sharingPanel(request, "Saved what this tenant shares with ${sharing.name}.")
    }

    /** The tenant drops its own choices for one support connection and follows the installation's preset there. */
    fun useConnectionPreset(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        val connectionId = connectionIdOf(request) ?: return ServerResponse.badRequest().build()
        val sharing = connectionSharing(tenantKey).firstOrNull { it.connectionId == connectionId } ?: return sharingPanel(request, null)
        sharing.views.forEach { it.section.followPreset(tenantKey, connectionId) }
        return sharingPanel(request, "This tenant uses the installation's setup for ${sharing.name} again.")
    }

    /** Saves the installation's preset for one support connection, as [saveInstallation] does for Epistola support. */
    fun saveConnectionPreset(request: ServerRequest): ServerResponse {
        requirePlatformRole(PlatformRole.INSTALLATION_ADMINISTRATOR)
        val connectionId = connectionIdOf(request) ?: return ServerResponse.badRequest().build()
        if (registry.tokenConnections().none { it.id == connectionId }) return ServerResponse.notFound().build()
        val form = request.params()
        val offered = sections.filter { it.offered(connectionId) }
        val chosen = offered.associate { it.key to form.field("${it.key}.default").orEmpty() }
        val shares = offered.any { section -> chosen[section.key] == REQUIRED || section.choices.any { it.value == chosen[section.key] && it.shares } }
        if (shares && form.field("agree") != "true") return redirect("/platform/support?presetError=$connectionId#support-connection-presets")
        applyPreset(chosen, form, connectionId, offered)
        return redirect("/platform/support#support-connection-presets")
    }

    /** Epistola support's connection is off: the panel shows the other connections alone. */
    private fun epistolaOff(): Boolean = registry.find(SupportConnections.DISCOVERED_ID)?.status()?.state == SupportConnectionState.Disabled

    private fun connectionIdOf(request: ServerRequest): UUID? = runCatching { UUID.fromString(request.pathVariable("connectionId")) }.getOrNull()

    /** The wizard's first step for [overview]: empty while nothing is set up, the choices in force when changing. */
    fun startWizard(overview: TenantSharingOverview): TenantSharingWizard = TenantSharingWizard(
        tenantKey = overview.tenantKey,
        tenantName = overview.tenantName,
        sections = overview.views.map { it.section },
        views = overview.views,
        step = 0,
        values = if (overview.state == TenantSharingState.NOT_SET_UP) emptyMap() else overview.views.associate { it.section.key to it.value },
        changing = overview.state != TenantSharingState.NOT_SET_UP,
    )

    /** The sharing panel alone, for "Cancel". */
    fun panel(request: ServerRequest): ServerResponse = sharingPanel(request, null)

    /** Opens the wizard, with the choices in force filled in. */
    fun setup(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        val overview = overview(tenantKey) ?: return sharingPanel(request, null)
        return wizardPanel(request, startWizard(overview))
    }

    /** Moves the wizard on or back, or finishes it: saves every choice as the tenant's own. */
    fun step(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        val overview = overview(tenantKey) ?: return sharingPanel(request, null)
        val form = request.params()
        val start = startWizard(overview)
        val values = start.sections.mapNotNull { s -> form.field("${s.key}.choice")?.takeIf { v -> s.choices.any { it.value == v } }?.let { s.key to it } }.toMap()
        val step = (form.field("step")?.toIntOrNull() ?: 0).coerceIn(0, start.lastStep)
        val wizard = start.copy(step = step, values = values, changing = form.field("changing") == "true")
        return when (form.field("action")) {
            "back" -> wizardPanel(request, wizard.copy(step = (step - 1).coerceAtLeast(0)))

            "finish" -> finish(request, wizard, form.field("agree") == "true")

            else -> {
                val section = wizard.section
                val value = section?.let { values[it.key] }
                when {
                    section != null && value == null -> wizardPanel(request, wizard.copy(error = "Choose one of the options to go on."))
                    section != null && value in wizard.view!!.withheld -> wizardPanel(request, wizard.copy(error = wizard.view!!.withheld[value]))
                    else -> wizardPanel(request, wizard.copy(step = (step + 1).coerceAtMost(wizard.lastStep)))
                }
            }
        }
    }

    private fun finish(request: ServerRequest, wizard: TenantSharingWizard, agreed: Boolean): ServerResponse {
        val missing = wizard.sections.firstOrNull { wizard.values[it.key] == null }
        if (missing != null) return wizardPanel(request, wizard.copy(step = wizard.sections.indexOf(missing) + 1, error = "Choose one of the options to go on."))
        // Withheld since the step was taken (Not allowed meanwhile): said in the wizard, before anything is saved.
        val withheld = wizard.sections.firstNotNullOfOrNull { s -> wizard.views.firstOrNull { it.section.key == s.key }?.withheld?.get(wizard.values.getValue(s.key))?.let { s to it } }
        if (withheld != null) return wizardPanel(request, wizard.copy(step = wizard.sections.indexOf(withheld.first) + 1, error = withheld.second))
        if (!agreed) return wizardPanel(request, wizard.copy(step = wizard.lastStep, error = "Tick that you agree on behalf of ${wizard.tenantName} to finish."))
        wizard.sections.forEach { it.applyOwn(wizard.tenantKey, wizard.values.getValue(it.key)) }
        return sharingPanel(request, if (wizard.changing) "Saved your changes." else "Saved. This is what ${wizard.tenantName} shares with Epistola now.")
    }

    /** The tenant drops its own choices and follows the installation preset again. */
    fun usePreset(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        sections.forEach { it.followPreset(tenantKey) }
        return sharingPanel(request, "This tenant uses the installation's setup again.")
    }

    /** The installation preset as a summary: what `/platform/support` shows, and where Cancel returns to. */
    fun installationPanel(request: ServerRequest): ServerResponse {
        requirePlatformRole(PlatformRole.INSTALLATION_ADMINISTRATOR)
        return defaultsPanel(request, editing = false)
    }

    /** The installation preset as a form, with the current preset chosen. */
    fun editInstallation(request: ServerRequest): ServerResponse {
        requirePlatformRole(PlatformRole.INSTALLATION_ADMINISTRATOR)
        return defaultsPanel(request, editing = true)
    }

    /**
     * Saves the installation preset; applied at once to every tenant without a choice of its own. A
     * preset that shares anything is agreed on those tenants' behalf (`agree=true`), as a tenant agrees
     * in its own setup; without it nothing is saved and the form says so.
     */
    fun saveInstallation(request: ServerRequest): ServerResponse {
        requirePlatformRole(PlatformRole.INSTALLATION_ADMINISTRATOR)
        val form = request.params()
        val chosen = sections.associate { it.key to form.field("${it.key}.default").orEmpty() }
        val shares = sections.any { section -> chosen[section.key] == REQUIRED || section.choices.any { it.value == chosen[section.key] && it.shares } }
        if (shares && form.field("agree") != "true") {
            return defaultsPanel(request, editing = true, chosen = chosen, error = "To share anything, agree on behalf of the tenants that use this setup.")
        }
        // A choice the form withholds (it was disabled, or became unavailable after the form opened) is refused with its reason.
        installationViews().firstNotNullOfOrNull { view -> view.withheld[chosen[view.section.key]] }?.let { reason ->
            return defaultsPanel(request, editing = true, chosen = chosen, error = reason)
        }
        val notices = applyPreset(chosen, form, SupportConnections.DISCOVERED_ID)
        return defaultsPanel(request, editing = false, notice = if (notices.isEmpty()) "Nothing changed." else "Saved. " + notices.joinToString(" "))
    }

    /** Applies the [chosen] preset and restrictions of every section for [connectionId]; what changed, one notice per section. */
    private fun applyPreset(chosen: Map<String, String>, form: Map<String, List<String>>, connectionId: UUID, among: List<TenantSharingSection> = sections): List<String> {
        val restrictions = GetSharingRestrictions(connectionId).query()
        return among.mapNotNull { section ->
            val current = restrictions.of(section.key)
            val wanted = when (chosen[section.key]) {
                NOT_ALLOWED -> SharingRestriction.NOT_ALLOWED
                REQUIRED -> SharingRestriction.REQUIRED.takeIf { section.offersRequired }
                else -> null
            }
            when {
                wanted != null && wanted == current -> null

                wanted == SharingRestriction.NOT_ALLOWED -> {
                    SetSharingRestriction(section.key, wanted, connectionId).execute()
                    "${section.title}: not allowed; no tenant shares it."
                }

                wanted == SharingRestriction.REQUIRED -> {
                    SetSharingRestriction(section.key, wanted, connectionId).execute()
                    "${section.title}: required for every tenant."
                }

                else -> {
                    if (current != null) SetSharingRestriction(section.key, null, connectionId).execute()
                    section.saveInstallation(form, connectionId) ?: if (current != null) "${section.title}: a choice for tenants again." else null
                }
            }
        }
    }

    private fun defaultsPanel(request: ServerRequest, editing: Boolean, chosen: Map<String, String> = emptyMap(), notice: String? = null, error: String? = null): ServerResponse = request.htmx {
        fragment("support/platform", "defaults") {
            "defaults" to installationViews()
            "defaultsEditing" to editing
            "defaultsChosen" to chosen
            "defaultsNotice" to notice
            "defaultsError" to error
        }
        onFullPage { redirect("/platform/support") }
    }

    private fun sharingPanel(request: ServerRequest, notice: String?, connectionError: Pair<UUID, String>? = null): ServerResponse {
        val tenantKey = request.tenantId().key
        val overview = overview(tenantKey)
        return request.htmx {
            fragment("support/overview", "sharing") {
                "tenantId" to tenantKey
                "sharing" to overview
                "wizard" to overview?.takeIf { it.offersSetup }?.let { startWizard(it) }
                "sharingNotice" to notice
                "connectionSharing" to connectionSharing(tenantKey)
                "epistolaOff" to epistolaOff()
                "connectionErrorId" to connectionError?.first
                "connectionError" to connectionError?.second
            }
            onFullPage { redirect("/tenants/${tenantKey.value}/support") }
        }
    }

    private fun wizardPanel(request: ServerRequest, wizard: TenantSharingWizard): ServerResponse {
        val tenantKey = request.tenantId().key
        return request.htmx {
            fragment("support/overview", "sharing") {
                "tenantId" to tenantKey
                "sharing" to overview(tenantKey)
                "wizard" to wizard
                "sharingNotice" to null
                "connectionSharing" to connectionSharing(tenantKey)
                "epistolaOff" to epistolaOff()
            }
            onFullPage { redirect("/tenants/${tenantKey.value}/support") }
        }
    }

    private fun canManage(tenantKey: TenantKey): Boolean = SecurityContext.current().hasPermission(tenantKey, Permission.INTEGRATIONS_MANAGE)
}

@Configuration
class SupportSharingRoutes(private val handler: SupportSharingHandler) {
    @Bean
    fun supportSharingRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/support".nest {
            GET("/sharing", handler::panel)
            GET("/setup", handler::setup)
            POST("/setup", handler::step)
            POST("/use-preset", handler::usePreset)
            POST("/connections/{connectionId}/sharing", handler::saveConnectionSharing)
            POST("/connections/{connectionId}/use-preset", handler::useConnectionPreset)
        }
        POST("/platform/support/connections/{connectionId}/defaults", handler::saveConnectionPreset)
        GET("/platform/support/defaults", handler::installationPanel)
        GET("/platform/support/defaults/edit", handler::editInstallation)
        POST("/platform/support/defaults", handler::saveInstallation)
    }
}
