// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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

        // The operator chose which build to deploy; we only detect whether it is a pre-release and
        // track the newest build on that same track (a pre-release build tracks the latest
        // pre-release when one is published, otherwise it references stable).
        val preRelease = current.isPreRelease
        val stable = product.stable
        val track = if (preRelease && product.prerelease?.version != null) product.prerelease else stable
        val latestVersion = track?.version
        val latest = latestVersion?.let { SemVersion.parse(it) }

        val minSupportedVersion = product.support?.minVersion
        val minSupported = minSupportedVersion?.let { SemVersion.parse(it) }
        // Supported unless we have a parseable floor and the running version is strictly below it.
        val supported = minSupported == null || current >= minSupported

        val supportedUntil = product.support?.until
        val supportEndingSoon = supported && withinDeprecationWindow(supportedUntil, checkedAt, deprecationWindow)

        // When unsupported, the upgrade target that clears the floor is the stable (GA) release, not
        // the pre-release an RC build otherwise tracks — so point the banner links there. Falls back
        // to the tracked release if no stable release is published.
        val linkRelease = if (!supported) (stable ?: track) else track

        return VersionCheckStatus(
            checkedAt = checkedAt,
            currentVersion = currentVersion,
            preRelease = preRelease,
            latestVersion = latestVersion,
            updateAvailable = latest != null && latest > current,
            releaseUrl = linkRelease?.releaseUrl,
            changelogUrl = linkRelease?.changelogUrl,
            latestStableVersion = stable?.version,
            stableReleaseUrl = stable?.releaseUrl,
            stableChangelogUrl = stable?.changelogUrl,
            supported = supported,
            supportEndingSoon = supportEndingSoon,
            minSupportedVersion = minSupportedVersion,
            supportedUntil = supportedUntil,
            lastError = if (track == null || latest == null) "Release metadata did not include a comparable version" else null,
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
