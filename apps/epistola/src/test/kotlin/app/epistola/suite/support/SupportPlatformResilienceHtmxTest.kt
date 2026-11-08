// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.testing.FakeSupportFault
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * `/platform/support` when the support service is slow or failing, or another check holds the
 * cluster lock: every action answers within a few seconds, never with a 500, and says what goes on.
 * The deadline for one call (`read-timeout`) is five seconds here, longer than a page waits.
 */
class SupportPlatformResilienceHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var work: SupportBackgroundWork

    @BeforeEach
    fun forget() {
        support.clearFaults()
        store.forget()
        store.forgetServices()
        connection.invalidate()
        connection.resetBackoff()
    }

    @AfterEach
    fun heal() {
        support.clearFaults()
        support.start()
    }

    /**
     * Registers through the page and waits until the registration is pending. A page waits for
     * the support service only briefly, and finds the work busy while work from an earlier test
     * still runs; without a registration, a check has nothing to contact and rightly says it checked.
     */
    private fun registered() {
        assertThat(work.awaitIdle()).`as`("work left from an earlier test ends").isTrue()
        post("/platform/support/register")
        assertThat(work.awaitIdle()).`as`("the registration ends").isTrue()
        connection.invalidate()
        assertThat(connection.status().state).`as`("registered").isInstanceOf(SupportConnectionState.Pending::class.java)
    }

    private fun post(path: String, form: Map<String, String> = emptyMap()): Pair<Int, String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val body = LinkedMultiValueMap<String, String>().apply { form.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(path, HttpEntity(body, headers), String::class.java).let { it.statusCode.value() to it.body.orEmpty() }
    }

    /** Holds the cluster lock of the support connection on another database session, as a check on another node does. */
    private fun <T> whileAnotherNodeChecks(block: () -> T): T {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = CompletableFuture.runAsync {
            jdbi.useHandle<Exception> { handle ->
                handle.createQuery("SELECT pg_advisory_lock(:key)").bind("key", SupportConnectionStore.LOCK_KEY).mapTo(String::class.java).one()
                locked.countDown()
                release.await(6, TimeUnit.SECONDS)
                handle.createQuery("SELECT pg_advisory_unlock(:key)").bind("key", SupportConnectionStore.LOCK_KEY).mapTo(Boolean::class.java).one()
            }
        }
        locked.await(5, TimeUnit.SECONDS)
        return try {
            block()
        } finally {
            release.countDown()
            holder.join()
        }
    }

    @Test
    fun `check now while another node checks says so at once instead of waiting on the lock`() {
        post("/platform/support/register")

        whileAnotherNodeChecks {
            var answer = 0 to ""
            val took = measureTimeMillis { answer = post("/platform/support/check") }

            assertThat(took).isLessThan(2_000)
            assertThat(answer.first).isEqualTo(HttpStatus.OK.value())
            assertThat(answer.second).contains("id=\"support-connection\"").contains("already running")
        }
    }

    @Test
    fun `register while another node checks says so at once instead of waiting on the lock`() {
        whileAnotherNodeChecks {
            var answer = 0 to ""
            val took = measureTimeMillis { answer = post("/platform/support/register") }

            assertThat(took).isLessThan(2_000)
            assertThat(answer.first).isEqualTo(HttpStatus.OK.value())
            assertThat(answer.second).contains("already running")
        }
    }

    @Test
    fun `a check against a hanging service answers within a few seconds and carries on in the background`() {
        post("/platform/support/register")
        support.fault(FakeSupportFault.Hang(maxMillis = 20_000), "GET", "/api/v1/connection")

        var answer = 0 to ""
        val took = measureTimeMillis { answer = post("/platform/support/check") }

        assertThat(took).isLessThan(4_500)
        assertThat(answer.first).isEqualTo(HttpStatus.OK.value())
        assertThat(answer.second).contains("goes on in the background").contains("data-testid=\"support-checking\"")
    }

    @Test
    fun `check now against a service nobody listens on says the check failed and why, not that it checked`() {
        registered()
        support.stop()

        val (status, body) = post("/platform/support/check")

        assertThat(status).isEqualTo(HttpStatus.OK.value())
        val notice = body.substringAfter("data-testid=\"support-notice\"").substringBefore("</div>")
        assertThat(notice).contains("The check failed").contains("could not be reached (connection refused)")
            .`as`("says it failed once").doesNotContainIgnoringCase("failed: failed")
            .contains("keeps working").doesNotContain("Checked just now")
    }

    @ParameterizedTest
    @MethodSource("faults")
    fun `a failing service never turns an action into a 500`(fault: FakeSupportFault) {
        post("/platform/support/register")
        support.fault(fault)

        for (path in listOf("/platform/support/check", "/platform/support/register-again")) {
            val (status, body) = post(path, mapOf("confirmed" to "true"))
            assertThat(status).`as`(path).isEqualTo(HttpStatus.OK.value())
            assertThat(body).contains("id=\"support-connection\"")
        }
        assertThat(restTemplate.getForEntity("/platform/support", String::class.java).statusCode).isEqualTo(HttpStatus.OK)
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        fun faults() = listOf(
            FakeSupportFault.serverError(),
            FakeSupportFault.badGatewayHtml(),
            FakeSupportFault.unavailable(),
            FakeSupportFault.tooManyRequests(),
            FakeSupportFault.malformedJson(),
            FakeSupportFault.TruncatedBody(),
            FakeSupportFault.Oversized(32L * 1024 * 1024),
        )

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connect-timeout") { "1s" }
            registry.add("epistola.support.read-timeout") { "5s" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
