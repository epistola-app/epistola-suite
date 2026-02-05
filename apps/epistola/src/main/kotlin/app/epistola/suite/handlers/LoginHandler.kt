package app.epistola.suite.handlers

import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Handler for the login page.
 *
 * Serves the login template which shows:
 * - Form-based login for local development
 * - OAuth2 login for production
 */
@Component
class LoginHandler {

    fun loginPage(request: ServerRequest): ServerResponse =
        ServerResponse.ok().render("login")
}
