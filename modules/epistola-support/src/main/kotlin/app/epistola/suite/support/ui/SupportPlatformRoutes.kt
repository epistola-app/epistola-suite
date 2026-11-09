// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.ui

import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.CheckSupportConnection
import app.epistola.suite.support.connection.DisconnectSupportConnection
import app.epistola.suite.support.connection.GetSupportConnectionOverview
import app.epistola.suite.support.connection.ListSupportConnections
import app.epistola.suite.support.connection.RegisterSupportConnection
import app.epistola.suite.support.connection.RegisterSupportConnectionAgain
import app.epistola.suite.support.connection.SaveSupportServices
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionCheckScheduler
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStatus
import app.epistola.suite.support.connection.SupportDisconnectOutcome
import app.epistola.suite.support.connection.SupportRegistrationNotAllowedException
import app.epistola.suite.support.connection.SupportUnavailableException
import app.epistola.suite.support.connection.SupportWorkAttempt
import app.epistola.suite.support.privacy.GetSupportPrivacy
import app.epistola.suite.validation.ValidationException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import java.time.Duration

/**
 * `/platform/support` — the installation's connection to Epistola support: its state, the claim
 * code while it waits for activation, the services selected here and those Epistola granted, and the
 * last check. A platform page (no tenant); the mediator enforces the installation administrator
 * role on every route.
 */
@Configuration
class SupportPlatformRoutes(private val handler: SupportPlatformHandler) {
    @Bean
    fun supportPlatformRouterFunction(): RouterFunction<ServerResponse> = router {
        "/platform/support".nest {
            GET("", handler::show)
            GET("/connection", handler::panel)
            POST("/check", handler::check)
            POST("/register", handler::register)
            POST("/register-again", handler::registerAgain)
            POST("/disconnect", handler::disconnect)
            POST("/services", handler::saveServices)
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
) {
    private val panels = panels.sortedBy { it.order }

    private fun panelViews() = panels.map { SupportPlatformPanelView(it.fragment, it.model()) }

    fun show(request: ServerRequest): ServerResponse = ServerResponse.ok().page("support/platform") {
        "pageTitle" to "Epistola support - Epistola"
        "platformPage" to "Epistola support"
        "overview" to GetSupportConnectionOverview().query()
        "checking" to work.isRunning(WORK_KEY)
        "privacy" to GetSupportPrivacy().query()
        "defaults" to sharing.installationViews()
        "connections" to ListSupportConnections().query()
        "panels" to panelViews()
    }

    /** The connection panel alone: what the panel polls while a check runs in the background. */
    fun panel(request: ServerRequest): ServerResponse = connectionPanel(request, if (work.isRunning(WORK_KEY)) STILL_RUNNING else null)

    /** Checks now; the connection panel is swapped in place, or the page reloads without HTMX. */
    fun check(request: ServerRequest): ServerResponse = connectionPanel(
        request,
        attempt { CheckSupportConnection().execute() }.notice { status ->
            status.lastError?.let { "${checkFailed(it)}. This installation keeps working, and tries again at the next check." } ?: "Checked just now."
        },
    )

    /** Registers now: while not registered, or after the claim code expired. */
    fun register(request: ServerRequest): ServerResponse {
        val company = request.param("companyName").orElse(null)
        val email = request.param("adminEmail").orElse(null)
        // Built here, so a contact that does not validate is said on the page, not lost on the background thread.
        val command = try {
            RegisterSupportConnection(company, email)
        } catch (e: ValidationException) {
            return connectionPanel(request, "Not registered: ${e.message}")
        }
        val notice = attempt { command.execute() }.notice { status ->
            if (status.state is SupportConnectionState.Pending) "Registered. Epistola will contact you to activate this installation." else status.lastError?.let { "Registering failed: $it" }
        }
        return connectionPanel(request, notice)
    }

    fun registerAgain(request: ServerRequest): ServerResponse {
        val confirmed = request.param("confirmed").map { it == "true" }.orElse(false)
        val notice = attempt { RegisterSupportConnectionAgain(confirmed).execute() }.notice { status ->
            if (status.state is SupportConnectionState.Pending) "Registered again. Epistola will contact you to activate this installation." else status.lastError?.let { "Registering failed: $it" }
        }
        return connectionPanel(request, notice)
    }

    /**
     * Disconnects: tells the support service, then forgets the registration and clears every tenant's
     * choice. When the service cannot be reached nothing changes, and the panel offers to remove the
     * connection here only (`localOnly=true`).
     */
    fun disconnect(request: ServerRequest): ServerResponse {
        val localOnly = request.param("localOnly").map { it == "true" }.orElse(false)
        var outcome: SupportDisconnectOutcome? = null
        val notice = try {
            attempt { DisconnectSupportConnection(localOnly).execute().also { outcome = it.outcome }.status }.notice {
                when (outcome) {
                    SupportDisconnectOutcome.CONFIRMED -> "Disconnected. Epistola support ended this installation's registration."
                    SupportDisconnectOutcome.ALREADY_GONE -> "Disconnected. Epistola support no longer accepted this registration, so there was nothing to end there."
                    else -> "Removed here only. Ask Epistola support to remove the registration and what it holds of this installation."
                }
            }
        } catch (e: SupportUnavailableException) {
            return connectionPanel(request, "Not disconnected: ${e.message}. Nothing changed. Try again, or remove the connection here only.", offerLocalOnly = true)
        }
        return connectionPanel(request, notice)
    }

    private sealed interface Outcome {
        data class Done(val status: SupportConnectionStatus) : Outcome

        data class Notice(val text: String?) : Outcome
    }

    private fun attempt(action: () -> SupportConnectionStatus): Outcome = try {
        when (val attempt = work.launchAndWait(WORK_KEY, INTERACTIVE_WAIT, action)) {
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

    /** Saves the selected services; no network I/O, so it works while the support service is down. */
    fun saveServices(request: ServerRequest): ServerResponse {
        val selected = request.params()["services"].orEmpty().toSet()
        val saved = SaveSupportServices(selected).execute()
        val notice = if (saved.services.isEmpty()) "Saved. This installation uses no support service." else "Saved. Selected: ${saved.services.joinToString(", ")}."
        return connectionPanel(request, notice)
    }

    private fun connectionPanel(request: ServerRequest, notice: String?, offerLocalOnly: Boolean = false): ServerResponse {
        val overview = GetSupportConnectionOverview().query()
        return request.htmx {
            fragment("support/platform", "connection") {
                "overview" to overview
                "notice" to notice
                "checking" to work.isRunning(WORK_KEY)
                "offerLocalOnly" to offerLocalOnly
                "privacy" to GetSupportPrivacy().query()
                "defaults" to sharing.installationViews()
                "connections" to ListSupportConnections().query()
                "connections" to ListSupportConnections().query()
                "panels" to panelViews()
            }
            onFullPage { redirect("/platform/support") }
        }
    }
}

private const val WORK_KEY = SupportConnectionCheckScheduler.WORK_KEY

/** How long a page waits for the support service before it carries on in the background. */
private val INTERACTIVE_WAIT: Duration = Duration.ofSeconds(3)

private const val BUSY = "A check or registration with Epistola support is already running. This panel shows its result shortly."

private const val STILL_RUNNING = "Epistola support is answering slowly; the check goes on in the background and this panel updates when it is done."

/**
 * "The check failed …" for a check's [lastError], saying "failed" once: most errors already start
 * with it ("Failed reading the support service metadata: …").
 */
internal fun checkFailed(lastError: String): String = if (lastError.startsWith("Failed ")) "The check failed ${lastError.removePrefix("Failed ")}" else "The check failed: $lastError"
