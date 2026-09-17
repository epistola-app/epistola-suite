// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_SESSION_ATTR
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.DefaultRedirectStrategy
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler

/**
 * Failure handler for SSO logins that tells a declined silent sign-in apart from a real failure.
 *
 * When the identity provider answers a `prompt=none` request with one of the OIDC "needs the user"
 * errors, the user simply has no provider session: they go to the plain login page, without an
 * error banner (and back into the popup when the login started there). Every other failure keeps
 * the existing `/login?error` behaviour.
 */
class SsoLoginFailureHandler : AuthenticationFailureHandler {

    companion object {
        /** The errors OIDC Core §3.1.2.6 defines for a `prompt=none` request the provider cannot satisfy silently. */
        val SILENT_LOGIN_DECLINED_ERRORS = setOf(
            "login_required",
            "interaction_required",
            "consent_required",
            "account_selection_required",
        )
    }

    private val log = LoggerFactory.getLogger(SsoLoginFailureHandler::class.java)
    private val redirectStrategy = DefaultRedirectStrategy()
    private val defaultFailureHandler = SimpleUrlAuthenticationFailureHandler("/login?error")

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val errorCode = (exception as? OAuth2AuthenticationException)?.error?.errorCode
        if (errorCode !in SILENT_LOGIN_DECLINED_ERRORS) {
            defaultFailureHandler.onAuthenticationFailure(request, response, exception)
            return
        }
        log.debug("Silent SSO login declined by the identity provider ({}); showing the login page", errorCode)
        val isPopup = request.getSession(false)?.getAttribute(POPUP_SESSION_ATTR) == true
        redirectStrategy.sendRedirect(request, response, if (isPopup) "/login?popup=true" else "/login")
    }
}
