// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.handlers.LoginHandler.Companion.silentLoginTarget
import app.epistola.suite.security.SilentLoginAuthorizationRequestResolver.Companion.SILENT_LOGIN_ATTEMPTED_ATTR
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.TestingAuthenticationToken

class LoginHandlerSilentLoginTest {

    private fun loginRequest(vararg params: Pair<String, String>) = MockHttpServletRequest("GET", "/login").apply {
        params.forEach { (name, value) -> setParameter(name, value) }
    }

    private fun target(request: MockHttpServletRequest, enabled: Boolean = true, registrationId: String? = "keycloak") = silentLoginTarget(request, enabled, registrationId)

    @Test
    fun `first visit tries a silent sign-in`() {
        assertThat(target(loginRequest())).isEqualTo("/oauth2/authorization/keycloak?silent=true")
    }

    @Test
    fun `popup mode is carried through`() {
        assertThat(target(loginRequest("popup" to "true")))
            .isEqualTo("/oauth2/authorization/keycloak?silent=true&popup=true")
    }

    @Test
    fun `context path is kept`() {
        val request = loginRequest().apply { contextPath = "/suite" }

        assertThat(target(request)).isEqualTo("/suite/oauth2/authorization/keycloak?silent=true")
    }

    @Test
    fun `disabled by configuration`() {
        assertThat(target(loginRequest(), enabled = false)).isNull()
    }

    @Test
    fun `no SSO configured`() {
        assertThat(target(loginRequest(), registrationId = null)).isNull()
    }

    @Test
    fun `never right after logout`() {
        assertThat(target(loginRequest("logout" to ""))).isNull()
    }

    @Test
    fun `never after a failed login`() {
        assertThat(target(loginRequest("error" to ""))).isNull()
    }

    @Test
    fun `only once per session`() {
        val request = loginRequest().apply { session.setAttribute(SILENT_LOGIN_ATTEMPTED_ATTR, true) }

        assertThat(target(request)).isNull()
    }

    @Test
    fun `not when already signed in`() {
        val request = loginRequest().apply { userPrincipal = TestingAuthenticationToken("user", "password") }

        assertThat(target(request)).isNull()
    }

    @Test
    fun `not inside an iframe, which identity providers refuse to be framed in`() {
        val request = loginRequest().apply { addHeader("Sec-Fetch-Dest", "iframe") }

        assertThat(target(request)).isNull()
    }

    @Test
    fun `a top-level navigation is not mistaken for an iframe`() {
        val request = loginRequest().apply { addHeader("Sec-Fetch-Dest", "document") }

        assertThat(target(request)).isEqualTo("/oauth2/authorization/keycloak?silent=true")
    }
}
