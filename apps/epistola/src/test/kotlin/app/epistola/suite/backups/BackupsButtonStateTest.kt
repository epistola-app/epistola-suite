package app.epistola.suite.backups

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * Regression guard: with the default **local** backup store the page can list backups
 * (`hubStatus = OK`), so the "Back up now" button must be **enabled**. A model/template name
 * mismatch once left it permanently disabled — this asserts the opening button tag carries no
 * `disabled` attribute.
 */
class BackupsButtonStateTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `back up now button is enabled when backups are listable`() {
        val tenant = createTenant("Backup Button")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/backups", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Back up now")

        // Extract just the opening <button ...> tag of the "Back up now" action and assert it is enabled.
        val textIdx = body.indexOf("Back up now")
        val tagStart = body.lastIndexOf("<button", textIdx)
        val openingTag = body.substring(tagStart, body.indexOf(">", tagStart) + 1)
        assertThat(openingTag).doesNotContain("disabled")
    }
}
