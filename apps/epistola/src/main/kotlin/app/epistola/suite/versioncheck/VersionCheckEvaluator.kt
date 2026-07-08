package app.epistola.suite.versioncheck

import app.epistola.suite.version.SemVersion
import java.time.Instant

object VersionCheckEvaluator {
    fun evaluate(
        document: EpistolaReleasesDocument,
        currentVersion: String,
        checkedAt: Instant,
    ): VersionCheckStatus {
        val current = SemVersion.parse(currentVersion)
            ?: return VersionCheckStatus(checkedAt = checkedAt, currentVersion = currentVersion)
        val product = document.products[VersionCheckService.PRODUCT_KEY]
            ?: return VersionCheckStatus(
                checkedAt = checkedAt,
                currentVersion = currentVersion,
                lastError = "Release metadata did not include ${VersionCheckService.PRODUCT_KEY}",
            )

        val channel = if (current.isPreRelease && product.prerelease?.version != null) {
            VersionCheckChannel.PRERELEASE
        } else {
            VersionCheckChannel.STABLE
        }
        val release = when (channel) {
            VersionCheckChannel.STABLE -> product.stable
            VersionCheckChannel.PRERELEASE -> product.prerelease
        }
        val latestVersion = release?.version
        val latest = latestVersion?.let { SemVersion.parse(it) }
        return VersionCheckStatus(
            checkedAt = checkedAt,
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            channel = channel,
            updateAvailable = latest != null && latest > current,
            releaseUrl = release?.releaseUrl,
            changelogUrl = release?.changelogUrl,
            lastError = if (release == null || latest == null) "Release metadata did not include a comparable $channel version" else null,
        )
    }
}
