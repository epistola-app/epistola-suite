// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

/**
 * Authorization request resolver that turns `/oauth2/authorization/{id}?silent=true` into a silent
 * OIDC sign-in: the request to the identity provider carries `prompt=none`, so a user with a live
 * provider session comes straight back signed in, and one without comes back with
 * `error=login_required` (handled by [SsoLoginFailureHandler]).
 *
 * The attempt is recorded in the session here, where `prompt=none` is actually sent, so the login
 * page never tries twice in one session — whatever link started the attempt.
 */
class SilentLoginAuthorizationRequestResolver(
    clientRegistrationRepository: ClientRegistrationRepository,
) : OAuth2AuthorizationRequestResolver {

    companion object {
        const val SILENT_PARAM = "silent"
        const val SILENT_LOGIN_ATTEMPTED_ATTR = "silent_login_attempted"
    }

    private val delegate = DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository)

    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? = silentIfRequested(request, delegate.resolve(request))

    override fun resolve(request: HttpServletRequest, clientRegistrationId: String): OAuth2AuthorizationRequest? = silentIfRequested(request, delegate.resolve(request, clientRegistrationId))

    private fun silentIfRequested(
        request: HttpServletRequest,
        authorizationRequest: OAuth2AuthorizationRequest?,
    ): OAuth2AuthorizationRequest? {
        if (authorizationRequest == null || request.getParameter(SILENT_PARAM) != "true") {
            return authorizationRequest
        }
        request.session.setAttribute(SILENT_LOGIN_ATTEMPTED_ATTR, true)
        return OAuth2AuthorizationRequest.from(authorizationRequest)
            .additionalParameters { it["prompt"] = "none" }
            .build()
    }
}
