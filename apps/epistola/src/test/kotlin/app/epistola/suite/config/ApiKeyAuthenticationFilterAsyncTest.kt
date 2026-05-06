package app.epistola.suite.config

import app.epistola.suite.api.security.ApiKeyAuthenticationFilter
import app.epistola.suite.api.security.ApiKeyAuthenticationToken
import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * Verifies ApiKeyAuthenticationFilter caches the validated principal on
 * REQUEST so the ASYNC dispatch (fired by Spring AI MCP's SSE deferred-result
 * completion) doesn't redo the DB lookup, double-record usage, or
 * double-count metrics.
 */
class ApiKeyAuthenticationFilterAsyncTest {

    private val service = ApiKeyService()
    private val meterRegistry = SimpleMeterRegistry()
    private val filter = ApiKeyAuthenticationFilter(service, meterRegistry)
    private lateinit var fakeMediator: RecordingMediator

    @BeforeEach
    fun setUp() {
        fakeMediator = RecordingMediator()
    }

    @AfterEach
    fun clearHolder() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `validates on REQUEST and restores from cache on ASYNC dispatch without re-querying`() {
        val plaintext = "epk_unit_test_key_value"
        val key = sampleApiKey()
        fakeMediator.lookupResponse = key

        val request = MockHttpServletRequest("POST", "/api/mcp").apply { addHeader("X-API-Key", plaintext) }
        val response = MockHttpServletResponse()

        // 1. REQUEST dispatch — filter should validate, cache, set holder, count.
        MediatorContext.runWithMediator(fakeMediator) {
            filter.doFilter(request, response, FilterChain { _, _ -> })
        }

        assertThat(fakeMediator.lookupQueries).isEqualTo(1)
        assertThat(fakeMediator.recordCommands).isEqualTo(1)
        assertThat(SecurityContextHolder.getContext().authentication).isInstanceOf(ApiKeyAuthenticationToken::class.java)
        val cached = request.getAttribute(ApiKeyAuthenticationFilter.REQUEST_ATTR_PRINCIPAL) as? EpistolaPrincipal
        assertThat(cached).isNotNull
        assertThat(meterRegistry.counter("epistola.api.auth.attempts", "result", "success").count()).isEqualTo(1.0)

        // 2. ASYNC re-dispatch on the SAME request object — filter should
        //    short-circuit, no re-lookup, no double-counting.
        SecurityContextHolder.clearContext()
        request.dispatcherType = DispatcherType.ASYNC

        MediatorContext.runWithMediator(fakeMediator) {
            filter.doFilter(request, response, FilterChain { _, _ -> })
        }

        assertThat(fakeMediator.lookupQueries)
            .withFailMessage("ASYNC dispatch should restore from cache, not re-query the DB")
            .isEqualTo(1)
        assertThat(fakeMediator.recordCommands)
            .withFailMessage("ASYNC dispatch should not double-record usage")
            .isEqualTo(1)
        assertThat(meterRegistry.counter("epistola.api.auth.attempts", "result", "success").count())
            .withFailMessage("ASYNC dispatch should not double-count the success counter")
            .isEqualTo(1.0)
        assertThat(SecurityContextHolder.getContext().authentication)
            .withFailMessage("ASYNC dispatch should restore the authentication on the holder")
            .isInstanceOf(ApiKeyAuthenticationToken::class.java)
    }

    private fun sampleApiKey(): ApiKey = ApiKey(
        id = ApiKeyKey.of(UUID.randomUUID()),
        tenantKey = TenantKey.of("test-tenant"),
        name = "test",
        keyPrefix = "epk_unit",
        enabled = true,
        createdAt = Instant.now(),
        lastUsedAt = null,
        expiresAt = null,
        createdBy = null,
    )

    /**
     * Counts the calls the filter makes — proves the ASYNC path doesn't
     * re-issue them.
     */
    private class RecordingMediator : Mediator {
        var lookupResponse: ApiKey? = null
        var lookupQueries: Int = 0
        var recordCommands: Int = 0

        override fun <R> send(command: app.epistola.suite.mediator.Command<R>): R {
            recordCommands += 1
            @Suppress("UNCHECKED_CAST")
            return Unit as R
        }

        @Suppress("UNCHECKED_CAST")
        override fun <R> query(query: app.epistola.suite.mediator.Query<R>): R {
            lookupQueries += 1
            return lookupResponse as R
        }
    }
}
