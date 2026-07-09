package app.epistola.suite.versioncheck

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VersionCheckEvaluatorTest {
    private val checkedAt = Instant.parse("2026-07-08T10:00:00Z")

    @Test
    fun `stable builds compare against stable channel only`() {
        val status = VersionCheckEvaluator.evaluate(
            document(
                stable = ReleaseChannel(version = "1.0.1"),
                prerelease = ReleaseChannel(version = "1.1.0-RC1"),
            ),
            currentVersion = "1.0.0",
            checkedAt = checkedAt,
        )

        assertThat(status.channel).isEqualTo(VersionCheckChannel.STABLE)
        assertThat(status.latestVersion).isEqualTo("1.0.1")
        assertThat(status.updateAvailable).isTrue()
    }

    @Test
    fun `prerelease builds compare against prerelease channel when present`() {
        val status = VersionCheckEvaluator.evaluate(
            document(
                stable = ReleaseChannel(version = "1.0.0"),
                prerelease = ReleaseChannel(version = "1.1.0-RC2"),
            ),
            currentVersion = "1.1.0-RC1-SNAPSHOT",
            checkedAt = checkedAt,
        )

        assertThat(status.channel).isEqualTo(VersionCheckChannel.PRERELEASE)
        assertThat(status.latestVersion).isEqualTo("1.1.0-RC2")
        assertThat(status.updateAvailable).isTrue()
    }

    @Test
    fun `dev builds do not report updates`() {
        val status = VersionCheckEvaluator.evaluate(
            document(stable = ReleaseChannel(version = "1.0.0")),
            currentVersion = "dev",
            checkedAt = checkedAt,
        )

        assertThat(status.updateAvailable).isFalse()
        assertThat(status.latestVersion).isNull()
    }

    @Test
    fun `versions below the minimum supported version are flagged unsupported`() {
        val status = VersionCheckEvaluator.evaluate(
            document(
                stable = ReleaseChannel(version = "1.2.0"),
                support = SupportPolicy(minVersion = "1.1.0", until = "2027-01-31"),
            ),
            currentVersion = "1.0.0",
            checkedAt = checkedAt,
        )

        assertThat(status.supported).isFalse()
        assertThat(status.minSupportedVersion).isEqualTo("1.1.0")
        assertThat(status.supportedUntil).isEqualTo("2027-01-31")
    }

    @Test
    fun `versions at or above the minimum supported version stay supported`() {
        val status = VersionCheckEvaluator.evaluate(
            document(
                stable = ReleaseChannel(version = "1.2.0"),
                support = SupportPolicy(minVersion = "1.1.0", until = "2027-01-31"),
            ),
            currentVersion = "1.1.0",
            checkedAt = checkedAt,
        )

        assertThat(status.supported).isTrue()
        assertThat(status.supportedUntil).isEqualTo("2027-01-31")
    }

    @Test
    fun `support ending within the window flags a deprecation warning`() {
        val status = VersionCheckEvaluator.evaluate(
            document(
                stable = ReleaseChannel(version = "1.2.0"),
                support = SupportPolicy(minVersion = "1.1.0", until = "2026-08-01"),
            ),
            currentVersion = "1.1.0",
            checkedAt = checkedAt, // 2026-07-08 → 24 days out, inside 90d
        )

        assertThat(status.supported).isTrue()
        assertThat(status.supportEndingSoon).isTrue()
    }

    @Test
    fun `support ending beyond the window does not warn`() {
        val status = VersionCheckEvaluator.evaluate(
            document(
                stable = ReleaseChannel(version = "1.2.0"),
                support = SupportPolicy(minVersion = "1.1.0", until = "2027-01-31"),
            ),
            currentVersion = "1.1.0",
            checkedAt = checkedAt, // 2026-07-08 → well beyond 90d
        )

        assertThat(status.supported).isTrue()
        assertThat(status.supportEndingSoon).isFalse()
    }

    @Test
    fun `unsupported versions do not also warn about ending support`() {
        val status = VersionCheckEvaluator.evaluate(
            document(
                stable = ReleaseChannel(version = "1.2.0"),
                support = SupportPolicy(minVersion = "1.1.0", until = "2026-08-01"),
            ),
            currentVersion = "1.0.0",
            checkedAt = checkedAt,
        )

        assertThat(status.supported).isFalse()
        assertThat(status.supportEndingSoon).isFalse()
    }

    @Test
    fun `absent support policy leaves the install supported`() {
        val status = VersionCheckEvaluator.evaluate(
            document(stable = ReleaseChannel(version = "1.2.0")),
            currentVersion = "1.0.0",
            checkedAt = checkedAt,
        )

        assertThat(status.supported).isTrue()
        assertThat(status.minSupportedVersion).isNull()
        assertThat(status.supportedUntil).isNull()
    }

    private fun document(
        stable: ReleaseChannel? = null,
        prerelease: ReleaseChannel? = null,
        support: SupportPolicy? = null,
    ) = EpistolaReleasesDocument(
        schemaVersion = 1,
        products = mapOf(
            VersionCheckService.PRODUCT_KEY to ProductReleases(
                stable = stable,
                prerelease = prerelease,
                support = support,
            ),
        ),
    )
}
