package app.epistola.suite.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
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
 * The cookie value is calculated as: session.lastAccessedTime + (session.maxInactiveInterval * 1000)
 *
 * Runs after SecurityFilter to ensure authentication is resolved.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2) // Run after SecurityFilter
class SessionExpiryCookieFilter : OncePerRequestFilter() {

    companion object {
        const val COOKIE_NAME = "session_expires_at"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        filterChain.doFilter(request, response)

        // Set cookie after request completes (session lastAccessedTime is updated)
        val session = request.getSession(false)
        val authentication = SecurityContextHolder.getContext().authentication

        if (session != null && authentication?.isAuthenticated == true && authentication.principal != "anonymousUser") {
            val expiresAt = session.lastAccessedTime + (session.maxInactiveInterval * 1000L)

            val cookie = Cookie(COOKIE_NAME, expiresAt.toString())
            cookie.path = "/"
            cookie.isHttpOnly = false // JS needs to read this
            cookie.maxAge = session.maxInactiveInterval
            cookie.secure = request.isSecure
            response.addCookie(cookie)
        }
    }
}
