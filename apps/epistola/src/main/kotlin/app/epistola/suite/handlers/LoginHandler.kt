package app.epistola.suite.handlers

import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Handler for the login page and related endpoints.
 *
 * Serves:
 * - Login template (form-based for local, OAuth2 for production)
 * - Popup success page for session re-login
 */
@Component
class LoginHandler {

    /**
     * Renders the login page.
     * Supports popup mode (popup=true parameter) for session expiry re-login.
     */
    fun loginPage(request: ServerRequest): ServerResponse = ServerResponse.ok().render("login")

    /**
     * Renders the popup success page.
     * This page notifies the opener window via postMessage and closes the popup.
     */
    fun loginPopupSuccess(request: ServerRequest): ServerResponse = ServerResponse.ok().render("login-popup-success")
}
