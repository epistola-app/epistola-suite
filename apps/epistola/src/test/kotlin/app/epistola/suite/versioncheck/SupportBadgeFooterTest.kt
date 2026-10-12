// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.metadata.AppMetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import java.time.Instant

@Isolated("Uses the global app_metadata installation-check status")
class SupportBadgeFooterTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var metadata: AppMetadataService

    @Autowired
    private lateinit var installationService: InstallationService

    @Autowired
    private lateinit var badgeService: SupportBadgeService

    @BeforeEach
    @AfterEach
    fun reset() {
        metadata.setAs(InstallationCheckService.STATUS_KEY, InstallationCheckStatus())
        badgeService.invalidate()
    }

    @Test
    fun `every page shows the badge, also before any check succeeded`() {
        val tenant = createTenant("Support Badge")

        for (path in listOf("/tenants/${tenant.id.value}", "/")) {
            val response = restTemplate.getForEntity(path, String::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"support-badge\"")
            assertThat(response.body).contains("Support could not be checked")
            assertThat(response.body).contains("https://epistola.app/help/support-check-unavailable")
        }
    }

    @Test
    fun `a registered installation shows its support party and links its page`() {
        val tenant = createTenant("Support Badge Registered")
        val now = Instant.now()
        metadata.setAs(
            InstallationCheckService.STATUS_KEY,
            InstallationCheckStatus(
                lastAttemptAt = now,
                lastSuccessAt = now,
                result = InstallationCheckResult(
                    registered = true,
                    status = "supported",
                    supportedUntil = "2099-12-31",
                    supportPartyName = "Acme <Hosting>",
                    minVersion = "0.0.1",
                ),
            ),
        )
        badgeService.invalidate()

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java).body

        assertThat(body).contains("Supported by Acme &lt;Hosting&gt; until 31 Dec 2099")
        assertThat(body).contains("https://epistola.app/installation/${installationService.get().id}")
        assertThat(body).contains("rel=\"noopener noreferrer\"")
    }
}
