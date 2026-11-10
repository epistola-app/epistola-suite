// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.ui

import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.AddSupportConnection
import app.epistola.suite.support.connection.CheckSupportConnection
import app.epistola.suite.support.connection.DisconnectSupportConnection
import app.epistola.suite.support.connection.DiscoverSupportConnectionPreset
import app.epistola.suite.support.connection.GetSupportConnectionOverview
import app.epistola.suite.support.connection.ListSupportConnections
import app.epistola.suite.support.connection.RegisterSupportConnection
import app.epistola.suite.support.connection.RegisterSupportConnectionAgain
import app.epistola.suite.support.connection.RemoveSupportConnection
import app.epistola.suite.support.connection.SaveSupportServices
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionAuth
import app.epistola.suite.support.connection.SupportConnectionCheckScheduler
import app.epistola.suite.support.connection.SupportConnectionExistsException
import app.epistola.suite.support.connection.SupportConnectionPreset
import app.epistola.suite.support.connection.SupportConnectionPresetUnavailableException
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStatus
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.SupportDisconnectOutcome
import app.epistola.suite.support.connection.SupportRegistrationNotAllowedException
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.connection.SupportUnavailableException
import app.epistola.suite.support.connection.SupportWorkAttempt
import app.epistola.suite.support.connection.UpdateSupportConnection
import app.epistola.suite.support.privacy.GetSupportPrivacy
import app.epistola.suite.support.privacy.SetSupportConnectionPrivacy
import app.epistola.suite.support.privacy.SupportConnectionNotFoundException
import app.epistola.suite.support.privacy.TelemetryDetail
import app.epistola.suite.support.privacy.TenantIdentity
import app.epistola.suite.validation.ValidationException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import java.time.Duration
import java.util.UUID

/**
 * `/platform/support` — the installation's support connections, one list whatever each signs in with
 * (ADR 0053), with the installation's privacy and what tenants share; and
 * `/platform/support/connections/{id}` for one that registers with the installation's own key: its
 * state, the claim code while it waits for activation, the services selected here and those it
 * granted, and the last check. Platform pages (no tenant); the mediator enforces the installation
 * administrator role on every route.
 */
@Configuration
class SupportPlatformRoutes(private val handler: SupportPlatformHandler) {
    @Bean
    fun supportPlatformRouterFunction(): RouterFunction<ServerResponse> = router {
        "/platform/support".nest {
            GET("", handler::show)
            GET("/connections/new", handler::newConnection)
            GET("/connections/{connectionId}", handler::detail)
            GET("/connections/{connectionId}/connection", handler::panel)
            POST("/connections/{connectionId}/check", handler::check)
            POST("/connections/{connectionId}/register", handler::register)
            POST("/connections/{connectionId}/register-again", handler::registerAgain)
            POST("/connections/{connectionId}/disconnect", handler::disconnect)
            POST("/connections/{connectionId}/services", handler::saveServices)
            POST("/connections/{connectionId}/privacy", handler::saveConnectionPrivacy)
            POST("/connections/lookup", handler::lookUpConnection)
            POST("/connections", handler::addConnection)
            POST("/connections/{connectionId}", handler::updateConnection)
            POST("/connections/{connectionId}/remove", handler::removeConnection)
        }
    }
}

/**
 * The page's actions talk to the support service on [SupportBackgroundWork], never on the request
 * thread: a page waits for them at most [INTERACTIVE_WAIT], and when the service is slower than that,
 * says so and refreshes its panel until they are done. A check or registration already running, on
 * this or another node, is said to be running instead of waited for.
 */
@Component
class SupportPlatformHandler(
    private val work: SupportBackgroundWork,
    private val sharing: SupportSharingHandler,
    panels: List<SupportPlatformPanel>,
    private val supportConnections: SupportConnections,
) {
    private val panels = panels.sortedBy { it.order }

    private fun panelViews() = panels.map { SupportPlatformPanelView(it.fragment, it.model()) }

    fun show(request: ServerRequest): ServerResponse = page(request)

    /**
     * The page, with what the Add connection form shows: the [preset] looked up, or an [addError] with
     * the values given ([addForm]), answered with [status].
     */
    private fun page(request: ServerRequest, status: Int = 200): ServerResponse = ServerResponse.status(status).page("support/platform") {
        listModel(request).forEach { (k, v) -> k to v }
    }

    /** What the list page shows. */
    private fun listModel(request: ServerRequest): Map<String, Any?> = mapOf(
        "serviceNames" to SupportScope.entries.map { it.value },
        "allConnections" to supportConnections.all(),
        "pageTitle" to "Support connections - Epistola",
        "platformPage" to "Support connections",
        "privacy" to GetSupportPrivacy().query(),
        "connections" to ListSupportConnections().query(),
        "panels" to panelViews(),
    )

    /**
     * The page of one connection (ADR 0053 §1): its settings, privacy and removal, and for one that
     * registers with the installation's own key its registration, services and disconnect.
     */
    fun detail(request: ServerRequest): ServerResponse {
        val id = runCatching { UUID.fromString(request.pathVariable("connectionId")) }.getOrNull() ?: return ServerResponse.notFound().build()
        val listing = ListSupportConnections().query().firstOrNull { it.id == id } ?: return ServerResponse.notFound().build()
        return ServerResponse.ok().page("support/connection") {
            "pageTitle" to "${listing.name} - Epistola"
            "platformPage" to "Support connections"
            "c" to listing
            "defaults" to sharing.installationViews(id)
            "allConnections" to supportConnections.all()
            "serviceNames" to SupportScope.entries.map { it.value }
            if (listing.registers) connectionModel(id, notice = null, offerLocalOnly = false).forEach { (k, v) -> k to v }
            "connectionId" to id
            "connectionName" to listing.name
        }
    }

    /** The connection panel alone: what the panel polls while a check runs in the background. */
    fun panel(request: ServerRequest): ServerResponse {
        val id = registeringId(request) ?: return ServerResponse.notFound().build()
        return connectionPanel(request, id, if (work.isRunning(workKey(id))) STILL_RUNNING else null)
    }

    /** Checks now; the connection panel is swapped in place, or the page reloads without HTMX. */
    fun check(request: ServerRequest): ServerResponse {
        val id = registeringId(request) ?: return ServerResponse.notFound().build()
        return connectionPanel(
            request,
            id,
            attempt(id) { CheckSupportConnection(id).execute() }.notice { status ->
                status.lastError?.let { "${checkFailed(it)}. This installation keeps working, and tries again at the next check." } ?: "Checked just now."
            },
        )
    }

    /** Registers now: while not registered, or after the claim code expired. */
    fun register(request: ServerRequest): ServerResponse {
        val id = registeringId(request) ?: return ServerResponse.notFound().build()
        val company = request.param("companyName").orElse(null)
        val email = request.param("adminEmail").orElse(null)
        // Built here, so a contact that does not validate is said on the page, not lost on the background thread.
        val command = try {
            RegisterSupportConnection(company, email, id)
        } catch (e: ValidationException) {
            return connectionPanel(request, id, "Not registered: ${e.message}")
        }
        val notice = attempt(id) { command.execute() }.notice { status ->
            if (status.state is SupportConnectionState.Pending) "Registered. ${nameOf(id)} will contact you to activate this installation." else status.lastError?.let { "Registering failed: $it" }
        }
        return connectionPanel(request, id, notice)
    }

    fun registerAgain(request: ServerRequest): ServerResponse {
        val id = registeringId(request) ?: return ServerResponse.notFound().build()
        val confirmed = request.param("confirmed").map { it == "true" }.orElse(false)
        val notice = attempt(id) { RegisterSupportConnectionAgain(confirmed, id).execute() }.notice { status ->
            if (status.state is SupportConnectionState.Pending) "Registered again. ${nameOf(id)} will contact you to activate this installation." else status.lastError?.let { "Registering failed: $it" }
        }
        return connectionPanel(request, id, notice)
    }

    /**
     * Disconnects: tells the support service, then forgets the registration and clears every tenant's
     * choice. When the service cannot be reached nothing changes, and the panel offers to remove the
     * connection here only (`localOnly=true`).
     */
    fun disconnect(request: ServerRequest): ServerResponse {
        val id = registeringId(request) ?: return ServerResponse.notFound().build()
        val name = nameOf(id)
        val localOnly = request.param("localOnly").map { it == "true" }.orElse(false)
        var outcome: SupportDisconnectOutcome? = null
        val notice = try {
            attempt(id) { DisconnectSupportConnection(localOnly, id).execute().also { outcome = it.outcome }.status }.notice {
                when (outcome) {
                    SupportDisconnectOutcome.CONFIRMED -> "Disconnected. $name ended this installation's registration."
                    SupportDisconnectOutcome.ALREADY_GONE -> "Disconnected. $name no longer accepted this registration, so there was nothing to end there."
                    else -> "Removed here only. Ask $name to remove the registration and what it holds of this installation."
                }
            }
        } catch (e: SupportUnavailableException) {
            return connectionPanel(request, id, "Not disconnected: ${e.message}. Nothing changed. Try again, or remove the connection here only.", offerLocalOnly = true)
        }
        return connectionPanel(request, id, notice)
    }

    private sealed interface Outcome {
        data class Done(val status: SupportConnectionStatus) : Outcome

        data class Notice(val text: String?) : Outcome
    }

    private fun attempt(id: UUID, action: () -> SupportConnectionStatus): Outcome = try {
        when (val attempt = work.launchAndWait(workKey(id), INTERACTIVE_WAIT, action)) {
            is SupportWorkAttempt.Done -> Outcome.Done(attempt.value)
            SupportWorkAttempt.Busy -> Outcome.Notice(BUSY)
            SupportWorkAttempt.StillRunning -> Outcome.Notice(STILL_RUNNING)
        }
    } catch (e: SupportRegistrationNotAllowedException) {
        Outcome.Notice(e.message)
    }

    private fun Outcome.notice(done: (SupportConnectionStatus) -> String?): String? = when (this) {
        is Outcome.Done -> done(status)
        is Outcome.Notice -> text
    }

    /** Saves one other connection's privacy (ADR 0053 §1); a value it does not know is refused. */
    fun saveConnectionPrivacy(request: ServerRequest): ServerResponse {
        val id = runCatching { UUID.fromString(request.pathVariable("connectionId")) }.getOrNull() ?: return ServerResponse.badRequest().build()
        val identity = request.param("tenantIdentity").orElse("").let(TenantIdentity::parse) ?: return ServerResponse.badRequest().build()
        val telemetry = request.param("telemetry").orElse("").let(TelemetryDetail::parse) ?: return ServerResponse.badRequest().build()
        return try {
            SetSupportConnectionPrivacy(id, identity, telemetry).execute()
            redirect("/platform/support/connections/$id")
        } catch (_: SupportConnectionNotFoundException) {
            ServerResponse.notFound().build()
        }
    }

    /** The Add connection dialog (docs/dialog-forms.md): alone for the page's action, on the page for a direct visit. */
    fun newConnection(request: ServerRequest): ServerResponse = request.htmx {
        fragment("support/connection-new", "dialog") { "serviceNames" to SupportScope.entries.map { it.value } }
        onFullPage {
            page("support/platform") {
                listModel(request).forEach { (k, v) -> k to v }
                "openDialog" to true
            }
        }
    }

    /** Looks up the preset a domain publishes and fills the Add connection form with it, in place. */
    fun lookUpConnection(request: ServerRequest): ServerResponse {
        val domain = request.param("domain").orElse("").trim()
        if (domain.isEmpty()) return addFailed(request, "Enter the domain of the party to connect to.", mapOf("domain" to domain))
        val preset = try {
            DiscoverSupportConnectionPreset(domain).query()
        } catch (e: SupportConnectionPresetUnavailableException) {
            return addFailed(request, e.reason, mapOf("domain" to domain))
        }
        return request.htmx {
            fragment("support/connection-new", "connection-form") {
                "serviceNames" to SupportScope.entries.map { it.value }
                "addPreset" to preset
                "addForm" to mapOf(Pair("domain", domain))
            }
            onFullPage {
                page("support/platform") {
                    listModel(request).forEach { (k, v) -> k to v }
                    "openDialog" to true
                    "addPreset" to preset
                }
            }
        }
    }

    /** The dialog's error slot says [message] and the form keeps what was entered; a full page shows the dialog again. */
    private fun addFailed(request: ServerRequest, message: String, form: Map<String, String>): ServerResponse = request.htmx {
        dialogFormError("add-connection-error", message)
        onFullPage {
            page(422, "support/platform") {
                listModel(request).forEach { (k, v) -> k to v }
                "openDialog" to true
                "addError" to message
                "addForm" to form
            }
        }
    }

    /** Adds a connection from the dialog: from a preset or filled in by hand; then its page. */
    fun addConnection(request: ServerRequest): ServerResponse {
        val form = listOf("name", "endpoint", "token", "tenantIdentity", "telemetry", "auth", "domain").associateWith { request.param(it).orElse("").trim() }
        val services = request.params()["services"].orEmpty()
        return try {
            val id = AddSupportConnection(
                name = form.getValue("name"),
                endpoint = form.getValue("endpoint"),
                token = form.getValue("token"),
                services = services,
                tenantIdentity = TenantIdentity.parse(form.getValue("tenantIdentity")),
                telemetry = TelemetryDetail.parse(form.getValue("telemetry")),
                auth = request.param("auth").orElse("").let { v -> SupportConnectionAuth.entries.firstOrNull { it.name == v } } ?: SupportConnectionAuth.BEARER_TOKEN,
            ).execute()
            request.htmx {
                dialogLocation("/platform/support/connections/$id")
                onFullPage { redirect("/platform/support/connections/$id") }
            }
        } catch (e: ValidationException) {
            addFailed(request, e.message, form - "token")
        } catch (e: SupportConnectionExistsException) {
            addFailed(request, "A connection to ${e.endpoint} exists already.", form - "token")
        }
    }

    /** Saves a connection's name and services, and replaces its token when one is given. */
    fun updateConnection(request: ServerRequest): ServerResponse {
        val id = runCatching { UUID.fromString(request.pathVariable("connectionId")) }.getOrNull() ?: return ServerResponse.badRequest().build()
        return try {
            val escalatesTo = request.param("escalatesTo").orElse("").takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: return ServerResponse.badRequest().build() }
            UpdateSupportConnection(
                id,
                request.param("name").orElse(""),
                request.params()["services"].orEmpty(),
                request.param("token").orElse(null),
                escalatesTo = escalatesTo,
                changeEscalation = request.param("escalatesTo").isPresent,
            ).execute()
            redirect("/platform/support/connections/$id")
        } catch (_: ValidationException) {
            redirect("/platform/support/connections/$id")
        } catch (_: SupportConnectionNotFoundException) {
            ServerResponse.notFound().build()
        }
    }

    /** Removes a connection, any one. */
    fun removeConnection(request: ServerRequest): ServerResponse {
        val id = runCatching { UUID.fromString(request.pathVariable("connectionId")) }.getOrNull() ?: return ServerResponse.badRequest().build()
        RemoveSupportConnection(id).execute()
        return redirect("/platform/support#support-connections")
    }

    /** Saves the selected services; no network I/O, so it works while the support service is down. */
    fun saveServices(request: ServerRequest): ServerResponse {
        val id = registeringId(request) ?: return ServerResponse.notFound().build()
        val selected = request.params()["services"].orEmpty().toSet()
        val saved = SaveSupportServices(selected, id).execute()
        val notice = if (saved.services.isEmpty()) "Saved. This installation uses no service over this connection." else "Saved. Selected: ${saved.services.joinToString(", ")}."
        return connectionPanel(request, id, notice)
    }

    /** The connection the path names, when it registers with the installation's own key; null otherwise. */
    private fun registeringId(request: ServerRequest): UUID? {
        val id = runCatching { UUID.fromString(request.pathVariable("connectionId")) }.getOrNull() ?: return null
        return id.takeIf { supportConnections.find(it)?.auth == SupportConnectionAuth.PRIVATE_KEY_JWT }
    }

    private fun nameOf(id: UUID): String = supportConnections.find(id)?.name ?: "The support connection"

    /**
     * The [SupportBackgroundWork] key of a check or registration of connection [id]: the scheduled
     * check's own for the connection `epistola.support.*` configures, so a page asks while it runs.
     */
    private fun workKey(id: UUID): String = if (id == supportConnections.configuredId()) WORK_KEY else "$WORK_KEY:$id"

    private fun connectionModel(id: UUID, notice: String?, offerLocalOnly: Boolean): Map<String, Any?> = mapOf(
        "connectionId" to id,
        "connectionName" to nameOf(id),
        "overview" to GetSupportConnectionOverview(id).query(),
        "notice" to notice,
        "checking" to work.isRunning(workKey(id)),
        "offerLocalOnly" to offerLocalOnly,
    )

    private fun connectionPanel(request: ServerRequest, id: UUID, notice: String?, offerLocalOnly: Boolean = false): ServerResponse {
        val model = connectionModel(id, notice, offerLocalOnly)
        return request.htmx {
            fragment("support/platform", "connection") { model.forEach { (k, v) -> k to v } }
            onFullPage { redirect("/platform/support/connections/$id") }
        }
    }
}

private const val WORK_KEY = SupportConnectionCheckScheduler.WORK_KEY

/** How long a page waits for the support service before it carries on in the background. */
private val INTERACTIVE_WAIT: Duration = Duration.ofSeconds(3)

private const val BUSY = "A check or registration with this support connection is already running. This panel shows its result shortly."

private const val STILL_RUNNING = "The support connection is answering slowly; the check goes on in the background and this panel updates when it is done."

/**
 * "The check failed …" for a check's [lastError], saying "failed" once: most errors already start
 * with it ("Failed reading the support service metadata: …").
 */
internal fun checkFailed(lastError: String): String = if (lastError.startsWith("Failed ")) "The check failed ${lastError.removePrefix("Failed ")}" else "The check failed: $lastError"
