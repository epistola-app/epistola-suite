// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_SESSION_ATTR
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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

    // A standalone Logback context: the JVM-global one is shared with concurrently running tests.
    private val loggerContext = LoggerContext()
    private val logger = loggerContext.getLogger(SsoLoginFailureHandler::class.java.name) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val handler = SsoLoginFailureHandler(logger)

    @BeforeEach
    fun attachAppender() {
        loggerContext.start()
        logger.level = Level.DEBUG
        appender.context = loggerContext
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    private fun redirectFor(exception: AuthenticationException, request: MockHttpServletRequest = callback()): String? {
        val response = MockHttpServletResponse()
        handler.onAuthenticationFailure(request, response, exception)
        return response.redirectedUrl
    }

    private fun callback() = MockHttpServletRequest("GET", "/login/oauth2/code/keycloak")

    private fun oauth2Error(code: String, description: String? = null) = OAuth2AuthenticationException(OAuth2Error(code, description, null))

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
    fun `declined silent login is not a warning`() {
        redirectFor(oauth2Error("login_required"))

        assertThat(warnings()).isEmpty()
    }

    @Test
    fun `a real SSO failure lands on the SSO error, not the credentials one`() {
        assertThat(redirectFor(oauth2Error("access_denied"))).isEqualTo("/login?error=sso")
    }

    @Test
    fun `a non-OAuth2 failure on the SSO chain lands on the SSO error too`() {
        assertThat(redirectFor(BadCredentialsException("bad"))).isEqualTo("/login?error=sso")
    }

    // #714: the incident that took hours to find, a UserInfo endpoint answering with a signed JWT.
    @Test
    fun `a callback failure is logged at WARN with its OAuth2 code and description`() {
        redirectFor(
            oauth2Error(
                "invalid_user_info_response",
                "response contains invalid content type 'application/jwt'",
            ),
        )

        assertThat(warnings()).singleElement().satisfies({ line ->
            assertThat(line).contains("error=invalid_user_info_response")
            assertThat(line).contains("exception=OAuth2AuthenticationException")
            assertThat(line).contains("reason=\"response contains invalid content type 'application/jwt'\"")
        })
    }

    @Test
    fun `a failure without an OAuth2 error logs the exception class and message`() {
        redirectFor(BadCredentialsException("provisioning refused"))

        assertThat(warnings()).singleElement().satisfies({ line ->
            assertThat(line).contains("error=none")
            assertThat(line).contains("exception=BadCredentialsException")
            assertThat(line).contains("reason=\"provisioning refused\"")
        })
    }

    @Test
    fun `a provider description cannot forge log lines`() {
        redirectFor(oauth2Error("invalid_token_response\nforged", "bad\r\nINFO Login succeeded user=admin"))

        assertThat(warnings()).singleElement().satisfies({ line ->
            assertThat(line).doesNotContain("\n").doesNotContain("\r")
            assertThat(line).contains("error=invalid_token_response_forged")
        })
    }

    @Test
    fun `a long provider description is cut off`() {
        redirectFor(oauth2Error("invalid_token_response", "x".repeat(10_000)))

        assertThat(warnings().single()).hasSizeLessThan(500)
    }
}
