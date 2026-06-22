package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.testing.TestPrincipalUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Server-contract cover for the profile page. The injected test principal is a form-login style
 * principal (not an [org.springframework.security.oauth2.core.oidc.user.OidcUser]), so this also
 * exercises the no-token degrade path: the page renders identity + resolved roles and the
 * "no identity-provider token" note, and never leaks a raw token.
 */
class ProfileHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `profile page renders the signed-in user's identity`() {
        val response = get("/profile")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body)
            .contains("Profile")
            .contains(TestPrincipalUser.DISPLAY_NAME)
            .contains(TestPrincipalUser.EMAIL)
            .contains(TestPrincipalUser.EXTERNAL_ID)
    }

    @Test
    fun `profile page renders the resolved roles`() {
        val response = get("/profile")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // The test principal holds every tenant role as a global role plus the platform manager role.
        assertThat(response.body).contains("Global roles").contains("READER").contains("MANAGER")
        assertThat(response.body).contains("Platform roles").contains("TENANT_MANAGER")
    }

    @Test
    fun `profile page degrades gracefully without an OIDC token`() {
        val response = get("/profile")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("no identity-provider")
        // No token section table and, crucially, no raw token material on the page.
        assertThat(response.body).doesNotContain("tokenValue")
        assertThat(response.body).doesNotContain("eyJ") // JWT segments always start with this.
    }

    private fun get(url: String): ResponseEntity<String> = restTemplate.getForEntity(url, String::class.java)
}
