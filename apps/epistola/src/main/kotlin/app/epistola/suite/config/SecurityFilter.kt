package app.epistola.suite.config

import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that binds the authenticated user to the current request scope using ScopedValue.
 *
 * This filter runs after MediatorFilter to ensure both mediator and security context
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
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // Run after MediatorFilter
class SecurityFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication

        // Extract EpistolaPrincipal from authentication
        val principal = when {
            authentication == null || !authentication.isAuthenticated -> null

            // Direct EpistolaPrincipal (shouldn't happen normally, but support it)
            authentication.principal is EpistolaPrincipal ->
                authentication.principal as EpistolaPrincipal

            // Local development: Extract from UserDetails
            authentication.principal is org.springframework.security.core.userdetails.UserDetails -> {
                val userDetails = authentication.principal as org.springframework.security.core.userdetails.UserDetails
                // Try to extract EpistolaPrincipal via reflection (from LocalUserDetails)
                try {
                    val method = userDetails.javaClass.getDeclaredMethod("getPrincipal")
                    method.invoke(userDetails) as? EpistolaPrincipal
                } catch (e: Exception) {
                    null
                }
            }

            // OAuth2: Extract from OAuth2User wrapper
            authentication.principal is org.springframework.security.oauth2.core.user.OAuth2User -> {
                val oauth2User = authentication.principal as org.springframework.security.oauth2.core.user.OAuth2User
                // Try to extract EpistolaPrincipal via reflection (from OAuth2UserWrapper)
                try {
                    val method = oauth2User.javaClass.getDeclaredMethod("getPrincipal")
                    method.invoke(oauth2User) as? EpistolaPrincipal
                } catch (e: Exception) {
                    null
                }
            }

            else -> null
        }

        // Bind principal to SecurityContext if available
        if (principal != null) {
            SecurityContext.runWithPrincipal(principal) {
                filterChain.doFilter(request, response)
            }
        } else {
            // No authentication or couldn't extract principal
            // Allow through - SecurityConfig will handle authorization
            filterChain.doFilter(request, response)
        }
    }
}
