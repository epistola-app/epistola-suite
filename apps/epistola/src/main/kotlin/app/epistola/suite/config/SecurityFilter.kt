package app.epistola.suite.config

import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.EpistolaPrincipalHolder
import app.epistola.suite.security.SecurityContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that binds the authenticated user to the current request scope using ScopedValue.
 *
 * This filter runs after Spring Security's filter chain (order -100) to ensure the
 * authentication context is populated before we extract it. It also runs after
 * MediatorFilter (HIGHEST_PRECEDENCE) so that both mediator and security context
 * are available throughout the request lifecycle.
 *
 * The filter extracts the Spring Security authentication and converts it to an
 * EpistolaPrincipal, which is then bound to SecurityContext for use by command/query
 * handlers and other business logic.
 *
 * The principal is automatically unbound when the request completes due to
 * ScopedValue's automatic scope management.
 */
@Component
@Order(-99) // Run after Spring Security (-100) but before other filters
class SecurityFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication

        val principal = when {
            authentication == null || !authentication.isAuthenticated -> null
            authentication.principal is EpistolaPrincipal -> authentication.principal as EpistolaPrincipal
            authentication.principal is EpistolaPrincipalHolder ->
                (authentication.principal as EpistolaPrincipalHolder).epistolaPrincipal
            else -> {
                log.warn(
                    "Authenticated but unrecognized principal type: {}",
                    authentication.principal?.javaClass?.name,
                )
                null
            }
        }

        if (principal != null) {
            SecurityContext.runWithPrincipal(principal) {
                filterChain.doFilter(request, response)
            }
        } else {
            filterChain.doFilter(request, response)
        }
    }
}
