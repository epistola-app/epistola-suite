package app.epistola.suite.versioncheck

import app.epistola.suite.version.SemVersion
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object VersionCheckEvaluator {
    fun evaluate(
        document: EpistolaReleasesDocument,
        currentVersion: String,
        checkedAt: Instant,
        deprecationWindow: Duration = Duration.ofDays(90),
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

        val minSupportedVersion = product.support?.minVersion
        val minSupported = minSupportedVersion?.let { SemVersion.parse(it) }
        // Supported unless we have a parseable floor and the running version is strictly below it.
        val supported = minSupported == null || current >= minSupported

        val supportedUntil = product.support?.until
        val supportEndingSoon = supported && withinDeprecationWindow(supportedUntil, checkedAt, deprecationWindow)

        // When unsupported, the upgrade target that clears the floor is the stable (GA) release, not
        // the prerelease an RC install otherwise tracks — so point the banner links there. Falls back
        // to the tracked release if no stable channel is published.
        val linkRelease = if (!supported) (product.stable ?: release) else release

        return VersionCheckStatus(
            checkedAt = checkedAt,
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            channel = channel,
            updateAvailable = latest != null && latest > current,
            releaseUrl = linkRelease?.releaseUrl,
            changelogUrl = linkRelease?.changelogUrl,
            supported = supported,
            supportEndingSoon = supportEndingSoon,
            minSupportedVersion = minSupportedVersion,
            supportedUntil = supportedUntil,
            lastError = if (release == null || latest == null) "Release metadata did not include a comparable $channel version" else null,
        )
    }

    /** True when [until] (an ISO date) is on or before [checkedAt] + [window] — i.e. the end of
     *  support is within the deprecation-warning window (or already past). Unparseable dates never
     *  warn. */
    private fun withinDeprecationWindow(until: String?, checkedAt: Instant, window: Duration): Boolean {
        val untilDate = until?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        val cutoff = checkedAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(window.toDays())
        return !untilDate.isAfter(cutoff)
    }
}
