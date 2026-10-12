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
            releaseUrl = UntrustedResponse.httpsUrl(linkRelease?.releaseUrl),
            changelogUrl = UntrustedResponse.httpsUrl(linkRelease?.changelogUrl),
            latestStableVersion = stable?.version,
            stableReleaseUrl = UntrustedResponse.httpsUrl(stable?.releaseUrl),
            stableChangelogUrl = UntrustedResponse.httpsUrl(stable?.changelogUrl),
            supported = supported,
            supportEndingSoon = supportEndingSoon,
            minSupportedVersion = minSupportedVersion,
            supportedUntil = supportedUntil,
            advisories = product.advisories?.let { applicableAdvisories(it, current) },
            lastError = if (track == null || latest == null) "Release metadata did not include a comparable version" else null,
        )
    }

    private val SEVERITIES = listOf("critical", "high", "medium", "low")
    private val ADVISORY_ID = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")
    private const val MAX_ADVISORIES = 20

    /**
     * The advisories whose ranges include [current], most severe first, cleaned for display.
     * Malformed entries and ranges with an unparseable bound are skipped rather than guessed at.
     */
    fun applicableAdvisories(advisories: List<Advisory>, current: SemVersion): List<ApplicableAdvisory> = advisories
        .asSequence()
        .filter { advisory -> advisory.affected.any { inRange(current, it) } }
        .mapNotNull { advisory ->
            val id = advisory.id?.takeIf { ADVISORY_ID.matches(it) } ?: return@mapNotNull null
            val severity = advisory.severity?.lowercase()?.takeIf { it in SEVERITIES } ?: "unknown"
            ApplicableAdvisory(
                id = id,
                severity = severity,
                summary = UntrustedResponse.text(advisory.summary),
                fixedIn = advisory.fixedIn?.takeIf { SemVersion.parse(it) != null },
                url = UntrustedResponse.httpsUrl(advisory.url),
            )
        }
        .sortedBy { SEVERITIES.indexOf(it.severity).let { rank -> if (rank < 0) SEVERITIES.size else rank } }
        .take(MAX_ADVISORIES)
        .toList()

    internal fun inRange(version: SemVersion, range: AffectedRange): Boolean {
        val introduced = range.introduced?.let { if (it == "0") SemVersion.parse("0.0.0") else SemVersion.parse(it) }
            ?: return false
        if (version < introduced) return false
        range.fixed?.let { fixed -> return SemVersion.parse(fixed)?.let { version < it } ?: false }
        range.lastAffected?.let { last -> return SemVersion.parse(last)?.let { version <= it } ?: false }
        return true
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
