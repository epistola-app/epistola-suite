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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
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
 *
 * ## Async dispatches
 *
 * `OncePerRequestFilter` defaults to skipping ASYNC re-dispatches. We override
 * that and cache the principal as a request attribute on the original REQUEST
 * dispatch — Spring Security's propagation of the auth across async dispatches
 * isn't fully reliable with our STATELESS API chain, and re-extracting the
 * principal from `SecurityContextHolder` on the async thread depends on
 * `ApiKeyAuthenticationFilter` running and re-setting it. Reading from the
 * cached attribute is safer and free of dependencies on filter order.
 */
@Component
@Order(-99) // Run after Spring Security (-100) but before other filters
class SecurityFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Run on async re-dispatches too — see class KDoc. */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = resolvePrincipal(request)

        if (principal != null) {
            // Cache so the async re-dispatch (different thread, ScopedValue
            // not inherited) can see the same principal even if Spring
            // Security hasn't propagated `SecurityContextHolder` to it.
            request.setAttribute(REQUEST_ATTR_PRINCIPAL, principal)
            SecurityContext.runWithPrincipal(principal) {
                filterChain.doFilter(request, response)
            }
        } else {
            filterChain.doFilter(request, response)
        }
    }

    private fun resolvePrincipal(request: HttpServletRequest): EpistolaPrincipal? {
        // On async re-dispatches, prefer the cached principal — see class KDoc.
        (request.getAttribute(REQUEST_ATTR_PRINCIPAL) as? EpistolaPrincipal)?.let { return it }

        val authentication = SecurityContextHolder.getContext().authentication
        val authPrincipal = authentication?.principal

        return when {
            authentication == null || !authentication.isAuthenticated -> null
            authPrincipal is String -> null // AnonymousAuthenticationToken
            authPrincipal is EpistolaPrincipal -> authPrincipal
            authPrincipal is EpistolaPrincipalHolder -> try {
                authPrincipal.epistolaPrincipal
            } catch (e: Exception) {
                log.warn("Failed to extract principal from stale session — invalidating", e)
                request.session?.invalidate()
                null
            }
            // JWT bearer tokens: EpistolaJwtAuthenticationConverter stores principal in details
            authentication is JwtAuthenticationToken && authentication.details is EpistolaPrincipal ->
                authentication.details as EpistolaPrincipal
            else -> {
                log.warn(
                    "Authenticated but unrecognized principal type: {} (interfaces: {})",
                    authPrincipal?.javaClass?.name,
                    authPrincipal?.javaClass?.interfaces?.map { it.name },
                )
                null
            }
        }
    }

    companion object {
        /** Request attribute key for the cached principal — package-private so tests can poke it. */
        internal const val REQUEST_ATTR_PRINCIPAL = "app.epistola.suite.config.SecurityFilter.PRINCIPAL"
    }
}
