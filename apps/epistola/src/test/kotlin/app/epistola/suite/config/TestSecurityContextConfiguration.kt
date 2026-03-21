package app.epistola.suite.config

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Test configuration that binds a fully-privileged test principal to every HTTP request.
 *
 * In the test profile, Spring Security permits all requests without authentication,
 * so the production [SecurityFilter] doesn't find an authenticated user.
 * This filter ensures the ScopedValue-based [SecurityContext] is always populated
 * for the mediator's authorization enforcement.
 */
@TestConfiguration
class TestSecurityContextConfiguration {

    @Bean
    @Order(-98) // Run after SecurityFilter (-99) to override when no auth is present
    fun testPrincipalFilter(): OncePerRequestFilter = object : OncePerRequestFilter() {
        private val testPrincipal = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-000000000099"),
            externalId = "test-user",
            email = "test@example.com",
            displayName = "Test User",
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = null,
        )

        override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            filterChain: FilterChain,
        ) {
            if (SecurityContext.isBound()) {
                // Principal already bound (e.g., by production SecurityFilter) — pass through
                filterChain.doFilter(request, response)
            } else {
                SecurityContext.runWithPrincipal(testPrincipal) {
                    filterChain.doFilter(request, response)
                }
            }
        }
    }
}
