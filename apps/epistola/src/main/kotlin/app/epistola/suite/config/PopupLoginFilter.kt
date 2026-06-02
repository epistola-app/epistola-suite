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
        // Only the login / OAuth2 authorization entry points carry popup=true.
        // Crucially, gate the getParameter() call behind this path check: reading a
        // request parameter forces the servlet to parse the body, and this filter
        // runs at HIGHEST_PRECEDENCE — the same precedence as the character-encoding
        // filter. Parsing here, before the encoding is forced to UTF-8, would lock
        // the body to ISO-8859-1 and mangle diacritics in every form POST (e.g. a
        // template name). The popup flag travels as a query parameter on these GET
        // entry points, so this never needs to touch a POST body.
        val path = request.requestURI
        val isOAuth2Request = path.startsWith("/oauth2/authorization/")
        val isLoginRequest = path == "/login"

        if (isOAuth2Request || isLoginRequest) {
            val isPopup = request.getParameter(POPUP_PARAM) == "true"
            if (isPopup) {
                // Save popup mode to session for use after OAuth2 redirect chain completes
                request.session.setAttribute(POPUP_SESSION_ATTR, true)
            }
        }

        filterChain.doFilter(request, response)
    }
}
