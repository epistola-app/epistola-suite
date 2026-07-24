// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
                currentVersion = "0.9.0",
                latestVersion = "1.0.0",
                latestStableVersion = "1.0.0",
                updateAvailable = true,
                releaseUrl = "https://epistola.app/releases/epistola-suite/1.0.0",
                changelogUrl = "https://epistola.app/changelog",
            ),
        )

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Epistola v<span>1.0.0</span> is available")
        assertThat(response.body).contains("This instance is running v0.9.0.")
        assertThat(response.body).contains("https://epistola.app/releases/epistola-suite/1.0.0")
    }

    @Test
    fun `tenant home shows unsupported banner when the running version is below the floor`() {
        val tenant = createTenant("Version Check Unsupported")
        metadata.setAs(
            VersionCheckService.STATUS_KEY,
            VersionCheckStatus(
                checkedAt = Instant.parse("2026-07-08T10:00:00Z"),
                currentVersion = "1.0.0",
                latestVersion = "1.2.0",
                updateAvailable = true,
                supported = false,
                minSupportedVersion = "1.1.0",
                supportedUntil = "2027-01-31",
                releaseUrl = "https://epistola.app/releases/epistola-suite/1.2.0",
            ),
        )

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("version-unsupported-card")
        assertThat(response.body).contains("is no longer supported")
        assertThat(response.body).contains("Minimum supported version is v1.1.0.")
        assertThat(response.body).contains("Supported through 2027-01-31.")
        // The unsupported banner replaces the ordinary update banner (which is gated on supported).
        assertThat(response.body).doesNotContain("is available")
    }

    @Test
    fun `tenant home shows deprecation warning when support is ending soon`() {
        val tenant = createTenant("Version Check Ending")
        metadata.setAs(
            VersionCheckService.STATUS_KEY,
            VersionCheckStatus(
                checkedAt = Instant.parse("2026-07-08T10:00:00Z"),
                currentVersion = "1.1.0",
                latestVersion = "1.2.0",
                updateAvailable = true,
                supported = true,
                supportEndingSoon = true,
                minSupportedVersion = "1.1.0",
                supportedUntil = "2026-08-01",
                releaseUrl = "https://epistola.app/releases/epistola-suite/1.2.0",
            ),
        )

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("version-ending-card")
        assertThat(response.body).contains("Support ends on <span>2026-08-01</span>")
        assertThat(response.body).contains("Upgrade to v1.2.0 to stay supported.")
        // The amber deprecation banner supersedes the ordinary update banner.
        assertThat(response.body).doesNotContain("is available")
        assertThat(response.body).doesNotContain("version-unsupported-card")
    }

    @Test
    fun `tenant home shows pre-release banner acknowledging the build and referencing stable`() {
        val tenant = createTenant("Version Check Prerelease")
        metadata.setAs(
            VersionCheckService.STATUS_KEY,
            VersionCheckStatus(
                checkedAt = Instant.parse("2026-07-08T10:00:00Z"),
                currentVersion = "1.1.0-RC1",
                preRelease = true,
                latestVersion = "1.1.0-RC2",
                updateAvailable = true,
                releaseUrl = "https://epistola.app/releases/epistola-suite/1.1.0-RC2",
                changelogUrl = "https://epistola.app/pre/changelog",
                latestStableVersion = "1.0.0",
                stableReleaseUrl = "https://epistola.app/releases/epistola-suite/1.0.0",
                supported = true,
            ),
        )

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("version-prerelease-card")
        assertThat(response.body).contains("You're running a pre-release (v<span>1.1.0-RC1</span>)")
        assertThat(response.body).contains("v1.1.0-RC2 is available.")
        assertThat(response.body).contains("https://epistola.app/releases/epistola-suite/1.0.0")
        // The neutral pre-release banner supersedes the terracotta "update available" one.
        assertThat(response.body).doesNotContain("version-unsupported-card")
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
                updateAvailable = false,
            ),
        )

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).doesNotContain("is available")
        assertThat(response.body).doesNotContain("version-update-card")
    }
}
