// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.metadata.AppMetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * The "Shared with Epistola" platform page. The test profile points both checks at a discard
 * port, so "Run the checks now" fails fast and records what it tried to send.
 */
@Isolated("Uses the global app_metadata check status")
class DataSharingPageTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var metadata: AppMetadataService

    @Autowired
    private lateinit var installationService: InstallationService

    @Autowired
    private lateinit var badgeService: SupportBadgeService

    @AfterEach
    fun reset() {
        metadata.setAs(InstallationCheckService.STATUS_KEY, InstallationCheckStatus())
        badgeService.invalidate()
    }

    @Test
    fun `shows the settings and exactly what each check sends`() {
        val body = restTemplate.getForEntity("/platform/sharing", String::class.java).also {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
        }.body!!
        val id = installationService.get().id

        assertThat(body).contains("Shared with Epistola")
        assertThat(body).contains("epistola.sharing.installation-id")
        assertThat(body).contains(id.toString())
        // The version check identifies nothing; the installation check carries the id and version.
        val versionRequest = body.substringAfter("data-testid=\"sharing-version-request\"").substringBefore("</pre>")
        assertThat(versionRequest).contains("User-Agent: epistola-suite").doesNotContain(id.toString()).doesNotContain("X-Epistola")
        val installationRequest = body.substringAfter("data-testid=\"sharing-installation-request\"").substringBefore("</pre>")
        assertThat(installationRequest).contains("/installation/$id.json").contains("X-Epistola-Suite-Version")
        assertThat(body).contains("data-testid=\"sharing-badge\"")
        // The test profile names both endpoints and leaves Exchange off, so discovery is never fetched.
        assertThat(body).contains("Product discovery").doesNotContain("data-testid=\"sharing-discovery-request\"")
        assertThat(body).contains("the discovery document is never fetched")
    }

    @Test
    fun `running the checks now records the attempt and keeps the badge honest`() {
        val response = restTemplate.postForEntity(
            "/platform/sharing/check",
            HttpEntity(LinkedMultiValueMap<String, String>(), HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }),
            String::class.java,
        )
        assertThat(response.statusCode.value()).isLessThan(400)

        val status = metadata.getAs(InstallationCheckService.STATUS_KEY, InstallationCheckStatus::class.java)!!
        assertThat(status.lastAttemptAt).isNotNull()
        assertThat(status.lastSuccessAt).isNull()
        assertThat(status.lastRequest?.request?.url).endsWith("/installation/${installationService.get().id}.json")

        val body = restTemplate.getForEntity("/platform/sharing", String::class.java).body!!
        assertThat(body).contains("Support could not be checked")
        assertThat(body).contains("Last run:")
        // The version check reports its own last run too (it failed on the discard port).
        assertThat(body.substringAfter("<h2>Version check</h2>").substringBefore("</section>")).contains("Last run:")
    }

    @Test
    fun `the tenant list links the page`() {
        assertThat(restTemplate.getForEntity("/", String::class.java).body).contains("data-testid=\"platform-sharing-link\"")
    }
}
