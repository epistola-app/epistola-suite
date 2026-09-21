// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_SESSION_ATTR
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error

class SsoLoginFailureHandlerTest {

    private val handler = SsoLoginFailureHandler()

    private fun redirectFor(exception: AuthenticationException, request: MockHttpServletRequest = callback()): String? {
        val response = MockHttpServletResponse()
        handler.onAuthenticationFailure(request, response, exception)
        return response.redirectedUrl
    }

    private fun callback() = MockHttpServletRequest("GET", "/login/oauth2/code/keycloak")

    private fun oauth2Error(code: String) = OAuth2AuthenticationException(OAuth2Error(code))

    @ParameterizedTest
    @ValueSource(strings = ["login_required", "interaction_required", "consent_required", "account_selection_required"])
    fun `declined silent login shows the plain login page`(errorCode: String) {
        assertThat(redirectFor(oauth2Error(errorCode))).isEqualTo("/login")
    }

    @Test
    fun `declined silent login in the session-expiry popup stays in popup mode`() {
        val request = callback().apply { session.setAttribute(POPUP_SESSION_ATTR, true) }

        assertThat(redirectFor(oauth2Error("login_required"), request)).isEqualTo("/login?popup=true")
    }

    @Test
    fun `a real SSO failure keeps the error redirect`() {
        assertThat(redirectFor(oauth2Error("access_denied"))).isEqualTo("/login?error")
    }

    @Test
    fun `a non-OAuth2 failure keeps the error redirect`() {
        assertThat(redirectFor(BadCredentialsException("bad"))).isEqualTo("/login?error")
    }
}
