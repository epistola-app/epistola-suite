// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.security.SsoLoginFailureHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * The login page tells a failed SSO sign-in apart from wrong credentials (#714). An SSO failure
 * happens after the user signed in at the identity provider, so "invalid username or password"
 * sent operators and users looking for a password problem that did not exist.
 *
 * Which failure lands where is [SsoLoginFailureHandler]'s job, tested in its own unit test; this
 * covers the page rendering each one.
 */
class LoginPageErrorHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun loginPage(path: String): String {
        val response = restTemplate.getForEntity(path, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    @Test
    fun `an SSO failure says sign-in failed, not that the password was wrong`() {
        val html = loginPage(SsoLoginFailureHandler.FAILURE_URL)

        assertThat(html).contains("""data-testid="login-error-sso"""")
        assertThat(html).contains("Sign-in failed")
        assertThat(html).doesNotContain("Invalid username or password")
    }

    @Test
    fun `a form-login failure keeps the credentials wording`() {
        val html = loginPage("/login?error")

        assertThat(html).contains("""data-testid="login-error"""")
        assertThat(html).contains("Invalid username or password")
        assertThat(html).doesNotContain("Sign-in failed")
    }

    @Test
    fun `no error, no banner`() {
        val html = loginPage("/login?logout")

        assertThat(html).doesNotContain("login-error")
    }
}
