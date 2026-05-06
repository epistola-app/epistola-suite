package app.epistola.suite.config

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Verifies the SecurityFilter caches the principal on REQUEST and restores it
 * on the same request's ASYNC dispatch — the case Spring AI MCP's SSE
 * deferred-result completion creates.
 */
class SecurityFilterAsyncTest {

    private val filter = SecurityFilter()

    @AfterEach
    fun clearHolder() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `caches the principal on REQUEST and restores it on ASYNC dispatch`() {
        val principal = principal()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())

        val request = MockHttpServletRequest("POST", "/api/mcp")
        val response = MockHttpServletResponse()

        // 1. Original REQUEST dispatch — filter should bind ScopedValue and cache the attribute
        var capturedOnRequest: EpistolaPrincipal? = null
        filter.doFilter(request, response, FilterChain { _, _ -> capturedOnRequest = SecurityContext.currentOrNull() })

        assertThat(capturedOnRequest).isEqualTo(principal)
        assertThat(request.getAttribute(SecurityFilter.REQUEST_ATTR_PRINCIPAL)).isEqualTo(principal)

        // 2. ASYNC re-dispatch — different thread (simulated by clearing the holder),
        //    same request object. Filter must restore the principal from the request attribute.
        SecurityContextHolder.clearContext() // simulate different thread / lost holder
        request.dispatcherType = DispatcherType.ASYNC

        var capturedOnAsync: EpistolaPrincipal? = null
        filter.doFilter(request, response, FilterChain { _, _ -> capturedOnAsync = SecurityContext.currentOrNull() })

        assertThat(capturedOnAsync)
            .withFailMessage("ASYNC dispatch failed to restore the cached principal")
            .isEqualTo(principal)
    }

    @Test
    fun `passes through when no auth and no cached principal`() {
        val request = MockHttpServletRequest("POST", "/something")
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        // Filter should not bind anything; chain runs normally.
        assertThat(SecurityContext.currentOrNull()).isNull()
    }

    private fun principal() = EpistolaPrincipal(
        userId = UserKey.of(UUID.randomUUID()),
        externalId = "test",
        email = "test@example.com",
        displayName = "Test",
        tenantMemberships = emptyMap(),
        currentTenantId = null,
    )
}
