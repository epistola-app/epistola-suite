// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.CheckTrigger
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySourceRegistry
import app.epistola.suite.quality.text.TextEgress
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/** One provider as the quality settings page shows it. */
data class QualityProviderView(
    val id: QualitySourceId,
    val name: String,
    val egress: TextEgress,
    val triggers: Set<CheckTrigger>,
    /** Whether this installation configured it (a LanguageTool server's URL); the built-in engine always is. */
    val configured: Boolean,
    /** Whether a tenant switches it on; one that is not runs for every tenant with quality on. */
    val switchable: Boolean,
    /** Switched on for this tenant; always true for a provider that is not switchable. */
    val enabled: Boolean,
) {
    /**
     * Whether the page offers a switch: only for a provider a tenant switches on **and** this
     * installation configured. Switching on a provider with no server would record a choice that
     * cannot run; the page says why instead.
     */
    val canSwitch: Boolean get() = switchable && configured
}

/** The installation's quality providers, as one tenant has them. */
data class QualityProviderList(
    val providers: List<QualityProviderView>,
) {
    /** Whether the page has anything to save: at least one switch it offers. */
    val hasSwitches: Boolean get() = providers.any { it.canSwitch }
}

/** The quality providers of this installation, with this tenant's switches, for the quality settings page. */
data class ListQualityProviders(
    override val tenantKey: TenantKey,
) : Query<QualityProviderList>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TENANT_SETTINGS
}

@Component
class ListQualityProvidersHandler(
    private val registry: QualitySourceRegistry,
) : QueryHandler<ListQualityProviders, QualityProviderList> {
    override fun handle(query: ListQualityProviders): QualityProviderList {
        val enabled = GetEnabledQualityProviders(query.tenantKey).query().providerIds
        return QualityProviderList(
            registry.textCheckers().map { checker ->
                QualityProviderView(
                    id = checker.checkerId,
                    name = checker.displayName,
                    egress = checker.egress,
                    triggers = checker.triggers,
                    configured = checker.configured,
                    switchable = checker.switchable,
                    enabled = !checker.switchable || checker.checkerId in enabled,
                )
            },
        )
    }
}
