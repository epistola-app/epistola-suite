// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.api.security.sanitizeForLog
import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_SESSION_ATTR
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
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
 * error banner (and back into the popup when the login started there).
 *
 * Every other failure happened after the user signed in at the provider: the token exchange,
 * the JWKS, the UserInfo response, ID-token validation or provisioning. Spring Security logs those
 * at DEBUG only, so at the default level an operator saw nothing while users were turned away
 * (#714). This logs each one at WARN, with the OAuth2 error code and description and never a
 * token or response body, and sends the user to `/login?error=sso`. The login page then says
 * that sign-in failed instead of blaming a password that was never entered.
 */
class SsoLoginFailureHandler(
    private val log: Logger = LoggerFactory.getLogger(SsoLoginFailureHandler::class.java),
) : AuthenticationFailureHandler {

    companion object {
        /** The errors OIDC Core §3.1.2.6 defines for a `prompt=none` request the provider cannot satisfy silently. */
        val SILENT_LOGIN_DECLINED_ERRORS = setOf(
            "login_required",
            "interaction_required",
            "consent_required",
            "account_selection_required",
        )

        /** Where a failed SSO login lands: the login page with its SSO error. */
        const val FAILURE_URL = "/login?error=sso"

        /** A provider's description is free text; bound it so one response cannot flood the log. */
        private const val MAX_DESCRIPTION_LENGTH = 300
    }

    private val redirectStrategy = DefaultRedirectStrategy()
    private val defaultFailureHandler = SimpleUrlAuthenticationFailureHandler(FAILURE_URL)

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val error = (exception as? OAuth2AuthenticationException)?.error
        val errorCode = error?.errorCode
        if (errorCode !in SILENT_LOGIN_DECLINED_ERRORS) {
            // The description comes from Spring or the provider, the code may come from the
            // provider: both are sanitised. The exception itself is not logged, since its message
            // and cause can carry the response the provider sent.
            val description = (error?.description ?: exception.message).orEmpty().take(MAX_DESCRIPTION_LENGTH)
            log.warn(
                "SSO login failed after the identity provider redirected back error={} exception={} reason=\"{}\"",
                sanitizeForLog(errorCode ?: "none"),
                exception.javaClass.simpleName,
                sanitizeForLog(description),
            )
            defaultFailureHandler.onAuthenticationFailure(request, response, exception)
            return
        }
        log.debug("Silent SSO login declined by the identity provider; showing the login page error={}", errorCode)
        val isPopup = request.getSession(false)?.getAttribute(POPUP_SESSION_ATTR) == true
        redirectStrategy.sendRedirect(request, response, if (isPopup) "/login?popup=true" else "/login")
    }
}
