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
            assertThat(response.body).contains("https://epistola.invalid/help/support-check-unavailable")
            assertThat(badgeIcons(response.body!!)).containsExactly("shield-question")
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
        assertThat(body).contains("https://epistola.invalid/installation/${installationService.get().id}")
        assertThat(body).contains("rel=\"noopener noreferrer\"")
        assertThat(badgeIcons(body!!)).containsExactly("shield-check")
    }

    @Test
    fun `the icon follows the badge state, so it does not rest on colour alone`() {
        val tenant = createTenant("Support Badge Icons")
        val now = Instant.now()
        val cases = mapOf(
            InstallationCheckResult(registered = true, status = "revoked") to "shield-alert",
            InstallationCheckResult(registered = false) to "shield",
        )

        for ((result, icon) in cases) {
            metadata.setAs(
                InstallationCheckService.STATUS_KEY,
                InstallationCheckStatus(lastAttemptAt = now, lastSuccessAt = now, result = result),
            )
            badgeService.invalidate()

            val body = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java).body!!

            assertThat(badgeIcons(body)).describedAs(result.toString()).containsExactly(icon)
        }
    }

    /** The sprite icons inside the support badge link, in order. */
    private fun badgeIcons(body: String): List<String> {
        val badge = Regex("""<a[^>]*data-testid="support-badge"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: error("support badge not rendered")
        return Regex("""\.svg#icon-([a-z0-9-]+)""").findAll(badge).map { it.groupValues[1] }.toList()
    }
}
