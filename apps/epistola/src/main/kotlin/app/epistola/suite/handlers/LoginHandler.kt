package app.epistola.suite.handlers

import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Handler for the login page and related endpoints.
 *
 * Serves:
 * - Login template (form-based when UserDetailsService exists, OAuth2 when registrations configured)
 * - Popup success page for session re-login
 */
@Component
class LoginHandler(
    private val userDetailsService: UserDetailsService? = null,
    private val clientRegistrationRepository: ClientRegistrationRepository? = null,
) {

    /**
     * Renders the login page.
     * Supports popup mode (popup=true parameter) for session expiry re-login.
     *
     * Shows:
     * - Form login when a UserDetailsService bean is present
     * - OAuth2 button when OAuth2 client registrations are configured
     * - Both when running with e.g. 'local,keycloak' profiles
     */
    fun loginPage(request: ServerRequest): ServerResponse {
        val hasFormLogin = userDetailsService != null
        val hasOAuth2 = clientRegistrationRepository != null
        val oauth2RegistrationId = getFirstRegistrationId() ?: "keycloak"

        return ServerResponse.ok().render(
            "login",
            mapOf(
                "hasFormLogin" to hasFormLogin,
                "hasOAuth2" to hasOAuth2,
                "oauth2RegistrationId" to oauth2RegistrationId,
            ),
        )
    }

    /**
     * Gets the first OAuth2 registration ID from the repository.
     */
    private fun getFirstRegistrationId(): String? {
        val repo = clientRegistrationRepository
        return when (repo) {
            is InMemoryClientRegistrationRepository -> repo.iterator().asSequence().firstOrNull()?.registrationId
            is Iterable<*> -> (repo as Iterable<*>).firstOrNull()?.let {
                (it as? org.springframework.security.oauth2.client.registration.ClientRegistration)?.registrationId
            }
            else -> null
        }
    }

    /**
     * Renders the popup success page.
     * This page notifies the opener window via postMessage and closes the popup.
     */
    fun loginPopupSuccess(request: ServerRequest): ServerResponse = ServerResponse.ok().render("login-popup-success")
}
