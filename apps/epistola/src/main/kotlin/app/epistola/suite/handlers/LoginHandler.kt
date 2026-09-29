// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.embedding.EmbeddingProperties
import app.epistola.suite.security.AuthProperties
import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_PARAM
import app.epistola.suite.security.SilentLoginAuthorizationRequestResolver.Companion.SILENT_LOGIN_ATTEMPTED_ATTR
import app.epistola.suite.security.SilentLoginAuthorizationRequestResolver.Companion.SILENT_PARAM
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

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
    private val authProperties: AuthProperties,
    private val embeddingProperties: EmbeddingProperties,
) {

    /**
     * Renders the login page.
     * Supports popup mode (popup=true parameter) for session expiry re-login.
     *
     * Shows:
     * - Form login when a UserDetailsService bean is present
     * - OAuth2 button when OAuth2 client registrations are configured
     * - Both when running with e.g. 'local,keycloak' profiles
     *
     * With SSO configured it first tries a silent sign-in instead — see [silentLoginTarget].
     */
    fun loginPage(request: ServerRequest): ServerResponse {
        val registrationId = getFirstRegistrationId()
        silentLoginTarget(
            request.servletRequest(),
            authProperties.oidc.silentLogin,
            registrationId,
            embeddingProperties.enabled,
        )?.let {
            return ServerResponse.status(HttpStatus.FOUND).location(URI.create(it)).build()
        }
        val hasFormLogin = userDetailsService != null
        val hasOAuth2 = registrationId != null

        return ServerResponse.ok().render(
            "login",
            buildMap {
                put("hasFormLogin", hasFormLogin)
                put("hasOAuth2", hasOAuth2)
                if (registrationId != null) {
                    put("oauth2RegistrationId", registrationId)
                    put("ssoButtonLabel", authProperties.oidc.ssoButtonLabel)
                }
            },
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

    companion object {
        /**
         * Where to send a login-page request for a silent SSO sign-in, or null to render the page.
         *
         * A user who already has a session at the identity provider is signed in without clicking,
         * and returns to the page they were bounced off. The attempt is skipped when the page has an
         * outcome to show (`error`, `logout` — a silent sign-in would undo the logout), when it was
         * already made in this session, and when the user is signed in. Inside an iframe it is
         * skipped unless embedding is enabled: identity providers usually refuse to be framed, but a
         * deployment that enables embedding has its provider allow the embedding host, or the
         * regular sign-in in the frame would not work either. Popup mode is carried through so the
         * session-expiry popup renews without a click too.
         */
        internal fun silentLoginTarget(
            request: HttpServletRequest,
            silentLoginEnabled: Boolean,
            registrationId: String?,
            embeddingEnabled: Boolean = false,
        ): String? {
            val skip = !silentLoginEnabled ||
                registrationId == null ||
                request.getParameter("error") != null ||
                request.getParameter("logout") != null ||
                request.userPrincipal != null ||
                (request.getHeader("Sec-Fetch-Dest") == "iframe" && !embeddingEnabled) ||
                request.getSession(false)?.getAttribute(SILENT_LOGIN_ATTEMPTED_ATTR) == true
            if (skip) return null

            return UriComponentsBuilder.fromPath(request.contextPath + "/oauth2/authorization/{registrationId}")
                .queryParam(SILENT_PARAM, "true")
                .apply { if (request.getParameter(POPUP_PARAM) == "true") queryParam(POPUP_PARAM, "true") }
                .buildAndExpand(registrationId)
                .encode()
                .toUriString()
        }
    }
}
