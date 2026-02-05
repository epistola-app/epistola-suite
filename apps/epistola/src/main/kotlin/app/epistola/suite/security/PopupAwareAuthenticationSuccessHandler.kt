package app.epistola.suite.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler

/**
 * Authentication success handler that redirects to a popup success page when login
 * was initiated from a popup window (indicated by popup=true parameter).
 *
 * For popup logins, this redirects to /login-popup-success which notifies the opener
 * window via postMessage and closes the popup.
 *
 * For normal logins, delegates to the default SavedRequestAwareAuthenticationSuccessHandler
 * which redirects to the originally requested URL or the default success URL.
 */
class PopupAwareAuthenticationSuccessHandler : SavedRequestAwareAuthenticationSuccessHandler() {

    companion object {
        const val POPUP_PARAM = "popup"
        const val POPUP_SESSION_ATTR = "login_popup_mode"
        const val POPUP_SUCCESS_URL = "/login-popup-success"
    }

    init {
        // Default success URL for non-popup logins
        setDefaultTargetUrl("/")
        // Always use default unless saved request exists
        setAlwaysUseDefaultTargetUrl(false)
    }

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        // Check if this is a popup login
        val isPopup = isPopupLogin(request)

        if (isPopup) {
            // Clear the session attribute if it was set
            request.session?.removeAttribute(POPUP_SESSION_ATTR)

            // Redirect to popup success page
            redirectStrategy.sendRedirect(request, response, POPUP_SUCCESS_URL)
        } else {
            // Normal login - use default behavior (redirect to saved request or /)
            super.onAuthenticationSuccess(request, response, authentication)
        }
    }

    /**
     * Determines if this login was initiated from a popup window.
     * Checks both the request parameter (for form login) and session attribute (for OAuth2).
     */
    private fun isPopupLogin(request: HttpServletRequest): Boolean {
        // Check request parameter (form login)
        if (request.getParameter(POPUP_PARAM) == "true") {
            return true
        }

        // Check session attribute (OAuth2 - set by PopupLoginFilter)
        val session = request.getSession(false)
        if (session?.getAttribute(POPUP_SESSION_ATTR) == true) {
            return true
        }

        return false
    }
}
