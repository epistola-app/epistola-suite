package app.epistola.suite.config

import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_PARAM
import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_SESSION_ATTR
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that saves the popup login state to the session when OAuth2 login is initiated.
 *
 * OAuth2 login involves multiple redirects (to IdP and back), so the popup parameter
 * needs to be persisted in the session to survive these redirects.
 *
 * This filter checks for popup=true on OAuth2 authorization requests and saves the
 * state to the session. The PopupAwareAuthenticationSuccessHandler then reads this
 * state after successful authentication.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Run very early
class PopupLoginFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Check if this is an OAuth2 authorization request with popup=true
        val path = request.requestURI
        val isOAuth2Request = path.startsWith("/oauth2/authorization/")
        val isLoginRequest = path == "/login"
        val isPopup = request.getParameter(POPUP_PARAM) == "true"

        if (isPopup && (isOAuth2Request || isLoginRequest)) {
            // Save popup mode to session for use after OAuth2 redirect chain completes
            request.session.setAttribute(POPUP_SESSION_ATTR, true)
        }

        filterChain.doFilter(request, response)
    }
}
