// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.ui.BasePlaywrightTest
import app.epistola.suite.ui.htmxSettle
import com.microsoft.playwright.Locator
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat as assertThatValue

/**
 * `/platform/support` in a real browser, against a stand-in support service that plays each state:
 * registering and the claim code, Check now while another check runs or while the service hangs (the
 * panel then refreshes itself until the check is done), choosing the services, an expired claim code
 * and a refused registration.
 */
class SupportConnectionUiTest : BasePlaywrightTest() {
    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var work: SupportBackgroundWork

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun forget() {
        support.clearFaults()
        support.clearRoutes()
        support.start()
        support.claimCodeLifetime = Duration.ofDays(7)
        store.forget()
        store.forgetServices()
        connection.resetBackoff()
        connection.invalidate()
    }

    /** A check a test left running in the background does not reach into the next one. */
    @AfterEach
    fun settle() {
        support.clearFaults()
        support.start()
        work.awaitIdle()
    }

    private val panel: Locator get() = page.getByTestId("support-connection")

    private fun state(): Locator = panel.getByTestId("support-state")

    private fun notice(): Locator = panel.getByTestId("support-notice")

    /** The list of connections, then the page of the connection epistola.support.* configures, through its row's link. */
    private fun openPlatformPage() {
        gotoAndReady("/platform/support")
        page.locator("[data-connection='${connection.id}']").getByTestId("support-connection-open").click()
        assertThat(panel).isVisible()
    }

    private fun sideways(): Int = (page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt()

    private fun clickInPanel(testId: String) {
        panel.getByTestId(testId).click()
        page.htmxSettle()
    }

    private fun register() {
        // Applying for support: the administrator confirms who Epistola contacts.
        panel.getByTestId("support-register-company").fill("Gemeente Test")
        panel.getByTestId("support-register-email").fill("beheer@test.example")
        clickInPanel("support-register")
        assertThat(state()).hasText("Waiting for activation")
    }

    private fun serviceRow(name: String): Locator = panel.locator("[data-testid='support-services'] tr[data-scope='$name']")

    /** Holds the cluster lock of the support connection on another database session, as a check on another node does. */
    private fun <T> whileAnotherNodeChecks(block: () -> T): T {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = CompletableFuture.runAsync {
            jdbi.useHandle<Exception> { handle ->
                handle.createQuery("SELECT pg_advisory_lock(:key)").bind("key", store.lockKey()).mapTo(String::class.java).one()
                locked.countDown()
                release.await(30, TimeUnit.SECONDS)
                handle.createQuery("SELECT pg_advisory_unlock(:key)").bind("key", store.lockKey()).mapTo(Boolean::class.java).one()
            }
        }
        assertThatValue(locked.await(5, TimeUnit.SECONDS)).isTrue()
        return try {
            block()
        } finally {
            release.countDown()
            holder.join()
        }
    }

    @Test
    fun `register says Epistola will contact the administrator, and Check now while another node checks says it is already running`() {
        openPlatformPage()
        assertThat(state()).hasText("Not registered")
        assertThat(panel.getByTestId("support-register-explained")).isVisible()

        register()

        assertThat(notice()).hasText("Registered. Epistola support will contact you to activate this installation.")
        assertThat(panel.getByTestId("support-claim-code")).hasText(support.latestRegistration.claimCode)
        assertThat(panel.getByTestId("support-claim-code-expires")).containsText("If Epistola has not activated")
        assertThat(panel.getByTestId("support-register")).hasCount(0)

        whileAnotherNodeChecks {
            clickInPanel("support-check-now")
            assertThat(notice()).containsText("already running")
        }
        assertThat(panel.getByTestId("support-claim-code")).hasText(support.latestRegistration.claimCode)
    }

    @Test
    fun `Check now against a slow service says it goes on in the background, and the panel shows the result once it is done`() {
        openPlatformPage()
        register()
        // Epistola activates it meanwhile, but the service takes its time to say so: until the test lets it answer.
        support.activate(scopes = setOf("requests"))
        val answer = CountDownLatch(1)
        support.route("GET", "/api/v1/connection") { r ->
            answer.await(20, TimeUnit.SECONDS)
            val registration = support.registrations.getValue(r.clientId!!)
            FakeSupportResponse.json(
                200,
                """{"clientId":"${registration.clientId}","installationId":"${registration.installationId}","status":"ACTIVE","scopes":["requests"],"claimCodeExpiresAt":null,"activatedAt":"2026-11-05T14:02:11Z"}""",
            )
        }

        try {
            clickInPanel("support-check-now")

            assertThat(notice()).containsText("goes on in the background")
            assertThat(panel.getByTestId("support-checking")).hasCount(1)
        } finally {
            answer.countDown()
        }

        // The check finishes in the background; the panel, polling, shows its result and stops polling.
        assertThat(state()).hasText("Connected")
        assertThat(panel.getByTestId("support-checking")).hasCount(0)
        assertThat(notice()).hasCount(0)
    }

    @Test
    fun `an administrator saves the services, and each row shows selected, granted and in use`() {
        openPlatformPage()
        register()
        support.activate(scopes = setOf("requests", "mirror"))
        clickInPanel("support-check-now")
        assertThat(state()).hasText("Connected")
        assertThat(panel.getByTestId("support-services-source")).containsText("epistola.support.services")

        serviceRow("telemetry").getByTestId("support-service-select-telemetry").uncheck()
        serviceRow("mirror").getByTestId("support-service-select-mirror").uncheck()
        clickInPanel("support-services-save")

        assertThat(notice()).hasText("Saved. Selected: requests.")
        assertThat(serviceRow("requests").getByTestId("support-service-select-requests")).isChecked()
        assertThat(serviceRow("requests").getByTestId("support-service-granted")).hasText("Granted")
        assertThat(serviceRow("requests").getByTestId("support-service-in-use")).hasText("In use")
        assertThat(serviceRow("mirror").getByTestId("support-service-select-mirror")).not().isChecked()
        assertThat(serviceRow("mirror").getByTestId("support-service-granted")).hasText("Granted")
        assertThat(serviceRow("mirror").getByTestId("support-service-in-use")).hasText("Not in use")
        assertThat(serviceRow("telemetry").getByTestId("support-service-select-telemetry")).not().isChecked()
        assertThat(serviceRow("telemetry").getByTestId("support-service-granted")).hasText("Not granted")
        assertThat(serviceRow("telemetry").getByTestId("support-service-in-use")).hasText("Not in use")
        assertThat(panel.getByTestId("support-services-source")).containsText("no longer applies")

        // Saved, not just shown: the page opened again has the same selection.
        openPlatformPage()
        assertThat(serviceRow("requests").getByTestId("support-service-select-requests")).isChecked()
        assertThat(serviceRow("mirror").getByTestId("support-service-select-mirror")).not().isChecked()
    }

    @Test
    fun `a claim code that expired unlinked says so, and Register gets a new one`() {
        // Issued already expired: invalid_client counts as "gone" only once the claim code expired.
        support.claimCodeLifetime = Duration.ofMinutes(-1)
        openPlatformPage()
        register()
        support.claimCodeLifetime = Duration.ofDays(7)
        val first = support.latestRegistration
        support.expire(first.clientId)

        clickInPanel("support-check-now")

        assertThat(state()).hasText("Claim code expired")
        assertThat(panel.getByTestId("support-expired")).containsText("The claim code expired on")
        assertThat(panel.getByTestId("support-claim-code")).hasCount(0)

        register()

        val second = support.latestRegistration
        assertThatValue(second.clientId).isNotEqualTo(first.clientId)
        assertThat(panel.getByTestId("support-claim-code")).hasText(second.claimCode)
    }

    @Test
    fun `a refused installation says why, and Register again asks first and gets a new claim code`() {
        openPlatformPage()
        register()
        val first = support.latestRegistration
        support.activate(first.clientId)
        clickInPanel("support-check-now")
        assertThat(state()).hasText("Connected")
        support.revoke(first.clientId)

        clickInPanel("support-check-now")

        assertThat(state()).hasText("Refused")
        assertThat(panel.getByTestId("support-refused-reason")).containsText("invalid_client")

        panel.getByTestId("support-register-again").click()
        page.getByTestId("confirm-dialog-confirm").click()
        page.htmxSettle()

        assertThat(notice()).hasText("Registered again. Epistola support will contact you to activate this installation.")
        assertThat(state()).hasText("Waiting for activation")
        val second = support.latestRegistration
        assertThatValue(second.clientId).isNotEqualTo(first.clientId)
        assertThat(panel.getByTestId("support-claim-code")).hasText(second.claimCode)
    }

    // --- the tenant's feedback pages ---

    /** Activated, with the requests service granted, and the service taking support requests. */
    @Test
    fun `at phone width the support page does not scroll sideways`() {
        connection.registerNow()
        support.activate(scopes = setOf("telemetry"))
        connection.checkNow()

        page.setViewportSize(390, 844)
        gotoAndReady("/platform/support")
        assertThat(page.getByTestId("support-connections")).isVisible()
        assertThatValue(sideways()).`as`("/platform/support scrolls sideways").isLessThanOrEqualTo(0)

        openPlatformPage()
        assertThat(panel.getByTestId("support-services")).isVisible()
        assertThatValue(sideways()).`as`("the connection's page scrolls sideways").isLessThanOrEqualTo(0)
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connect-timeout") { "1s" }
            // Longer than the page's three-second wait, so a hanging check is still running when it answers.
            registry.add("epistola.support.read-timeout") { "10s" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
