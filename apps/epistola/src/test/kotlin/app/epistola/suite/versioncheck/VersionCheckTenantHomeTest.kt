package app.epistola.suite.versioncheck

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.metadata.AppMetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import java.time.Instant

@Isolated("Uses global app_metadata version-check status")
class VersionCheckTenantHomeTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var metadata: AppMetadataService

    @AfterEach
    fun resetVersionCheckStatus() {
        metadata.setAs(
            VersionCheckService.STATUS_KEY,
            VersionCheckStatus(
                checkedAt = Instant.parse("2026-07-08T10:00:00Z"),
                currentVersion = "1.0.0",
                latestVersion = "1.0.0",
                channel = VersionCheckChannel.STABLE,
                updateAvailable = false,
            ),
        )
    }

    @Test
    fun `tenant home shows update banner when cached status has a newer version`() {
        val tenant = createTenant("Version Check")
        metadata.setAs(
            VersionCheckService.STATUS_KEY,
            VersionCheckStatus(
                checkedAt = Instant.parse("2026-07-08T10:00:00Z"),
                currentVersion = "1.0.0-RC2",
                latestVersion = "1.0.0",
                channel = VersionCheckChannel.STABLE,
                updateAvailable = true,
                releaseUrl = "https://epistola.app/releases/epistola-suite/1.0.0",
                changelogUrl = "https://epistola.app/changelog",
            ),
        )

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Epistola v<span>1.0.0</span> is available")
        assertThat(response.body).contains("This instance is running v1.0.0-RC2.")
        assertThat(response.body).contains("https://epistola.app/releases/epistola-suite/1.0.0")
    }

    @Test
    fun `tenant home renders when cached status blob is unreadable`() {
        val tenant = createTenant("Version Check Drifted")
        // A blob whose shape no longer maps to VersionCheckStatus (e.g. written by an older build).
        // The DB is never reset, so such a row can survive an upgrade; it must not break the page.
        metadata.setAs(VersionCheckService.STATUS_KEY, "not-a-version-check-status")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).doesNotContain("version-update-card")
    }

    @Test
    fun `tenant home hides update banner when no update is available`() {
        val tenant = createTenant("Version Check Current")
        metadata.setAs(
            VersionCheckService.STATUS_KEY,
            VersionCheckStatus(
                checkedAt = Instant.parse("2026-07-08T10:00:00Z"),
                currentVersion = "1.0.0",
                latestVersion = "1.0.0",
                channel = VersionCheckChannel.STABLE,
                updateAvailable = false,
            ),
        )

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).doesNotContain("is available")
        assertThat(response.body).doesNotContain("version-update-card")
    }
}
