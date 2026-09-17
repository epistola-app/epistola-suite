// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.security.SilentLoginAuthorizationRequestResolver.Companion.SILENT_LOGIN_ATTEMPTED_ATTR
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLDecoder
import kotlin.text.Charsets.UTF_8

class SilentLoginAuthorizationRequestResolverTest {

    private val resolver = SilentLoginAuthorizationRequestResolver(
        InMemoryClientRegistrationRepository(
            ClientRegistration.withRegistrationId("keycloak")
                .clientId("epistola")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .authorizationUri("https://idp.example.com/auth")
                .tokenUri("https://idp.example.com/token")
                .jwkSetUri("https://idp.example.com/certs")
                .build(),
        ),
    )

    private fun authorizationRequest(vararg params: Pair<String, String>) = MockHttpServletRequest("GET", "/oauth2/authorization/keycloak").apply {
        servletPath = "/oauth2/authorization/keycloak"
        params.forEach { (name, value) -> setParameter(name, value) }
    }

    private fun queryParams(uri: String) = UriComponentsBuilder.fromUriString(uri).build().queryParams

    @Test
    fun `silent request sends prompt=none and records the attempt`() {
        val request = authorizationRequest("silent" to "true")

        val resolved = requireNotNull(resolver.resolve(request))

        val params = queryParams(resolved.authorizationRequestUri)
        assertThat(params.getFirst("prompt")).isEqualTo("none")
        // The rebuilt request keeps what the default resolver generated.
        assertThat(URLDecoder.decode(params.getFirst("state"), UTF_8)).isEqualTo(resolved.state)
        assertThat(params.getFirst("nonce")).isNotBlank()
        assertThat(params.getFirst("client_id")).isEqualTo("epistola")
        assertThat(request.getSession(false)?.getAttribute(SILENT_LOGIN_ATTEMPTED_ATTR)).isEqualTo(true)
    }

    @Test
    fun `silent request through the explicit registration overload also sends prompt=none`() {
        val request = authorizationRequest("silent" to "true")

        val resolved = requireNotNull(resolver.resolve(request, "keycloak"))

        assertThat(queryParams(resolved.authorizationRequestUri).getFirst("prompt")).isEqualTo("none")
        assertThat(request.getSession(false)?.getAttribute(SILENT_LOGIN_ATTEMPTED_ATTR)).isEqualTo(true)
    }

    @Test
    fun `button click stays interactive and records nothing`() {
        val request = authorizationRequest()

        val resolved = requireNotNull(resolver.resolve(request))

        assertThat(queryParams(resolved.authorizationRequestUri).containsKey("prompt")).isFalse()
        assertThat(request.getSession(false)).isNull()
    }

    @Test
    fun `other paths resolve to nothing even with silent=true`() {
        val request = MockHttpServletRequest("GET", "/tenants").apply {
            servletPath = "/tenants"
            setParameter("silent", "true")
        }

        assertThat(resolver.resolve(request)).isNull()
        assertThat(request.getSession(false)).isNull()
    }
}
