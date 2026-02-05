package app.epistola.suite.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that sets a session_expires_at cookie after each authenticated request.
 *
 * This cookie is readable by JavaScript (httpOnly=false) and contains the Unix timestamp
 * (in milliseconds) when the session will expire. The frontend session-monitor.js uses
 * this to warn users before session expiry and show a re-login popup when expired.
 *
 * The cookie value is calculated as: current time + session timeout.
 *
 * Runs AFTER Spring Security filter chain to ensure authentication is resolved.
 * Spring Security runs at order -100, so -99 ensures this filter runs immediately after.
 */
@Component
@Order(-99) // Run after Spring Security (which runs at -100)
class SessionExpiryCookieFilter : OncePerRequestFilter() {

    companion object {
        const val COOKIE_NAME = "session_expires_at"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Set cookie BEFORE filter chain runs (response headers not yet committed)
        // This runs after Spring Security has authenticated the user
        val session = request.getSession(false)
        val authentication = SecurityContextHolder.getContext().authentication

        if (session != null && authentication?.isAuthenticated == true && authentication.principal != "anonymousUser") {
            // Calculate expiry: current time + session timeout
            // Using current time instead of lastAccessedTime since this request will update it
            val expiresAt = System.currentTimeMillis() + (session.maxInactiveInterval * 1000L)

            val cookie = Cookie(COOKIE_NAME, expiresAt.toString())
            cookie.path = "/"
            cookie.isHttpOnly = false // JS needs to read this
            cookie.maxAge = session.maxInactiveInterval
            cookie.secure = request.isSecure
            response.addCookie(cookie)
        }

        filterChain.doFilter(request, response)
    }
}
