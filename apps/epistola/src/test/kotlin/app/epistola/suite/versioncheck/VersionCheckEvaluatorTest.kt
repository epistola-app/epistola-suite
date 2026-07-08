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

    private fun document(
        stable: ReleaseChannel? = null,
        prerelease: ReleaseChannel? = null,
    ) = EpistolaReleasesDocument(
        schemaVersion = 1,
        products = mapOf(
            VersionCheckService.PRODUCT_KEY to ProductReleases(
                stable = stable,
                prerelease = prerelease,
            ),
        ),
    )
}
