package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * Verifies the admin Features page renders feature display metadata: the human title (not the raw
 * key) and the maturity badge for non-stable features (Backups is Beta).
 */
class FeatureTogglePageHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `features page shows titles and the beta badge for backups`() {
        val tenant = createTenant("Features Page")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/features", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        // The Backups row renders its title and the Beta maturity badge.
        assertThat(body).contains("Backups")
        assertThat(body).contains("badge badge-beta")
        assertThat(body).contains(">Beta<")
        // The toggle checkbox still posts under the raw feature key.
        assertThat(body).contains("name=\"support-backups\"")
    }
}
