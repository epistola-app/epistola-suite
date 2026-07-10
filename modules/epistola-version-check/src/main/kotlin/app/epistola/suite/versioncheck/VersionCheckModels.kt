package app.epistola.suite.versioncheck

import java.time.Instant

/**
 * Cached outcome of a version check. There is no "channel" the installation subscribes to — the
 * operator chooses which build to deploy, and [preRelease] simply reflects whether that build is a
 * pre-release. [latestVersion]/[updateAvailable] track the newest build on the running build's own
 * track (newer stable for a stable build, newer pre-release for a pre-release build), while
 * [latestStableVersion] always carries the newest stable release for reference.
 */
data class VersionCheckStatus(
    val checkedAt: Instant? = null,
    val currentVersion: String? = null,
    val metadataAvailable: Boolean = true,
    /** True when the running build is itself a pre-release (an RC/beta), not a final release. */
    val preRelease: Boolean = false,
    /** Newest version on the running build's own track (stable→stable, pre-release→pre-release). */
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val releaseUrl: String? = null,
    val changelogUrl: String? = null,
    /** Newest stable release, always populated when known — the reference a pre-release build shows. */
    val latestStableVersion: String? = null,
    val stableReleaseUrl: String? = null,
    val stableChangelogUrl: String? = null,
    /** False when the running version is below the product's minimum supported version. */
    val supported: Boolean = true,
    /** True when still supported but [supportedUntil] falls within the deprecation-warning window. */
    val supportEndingSoon: Boolean = false,
    /** The product's minimum supported version, when the metadata declares one. */
    val minSupportedVersion: String? = null,
    /** ISO date (`YYYY-MM-DD`) until which the minimum supported version is supported, if declared. */
    val supportedUntil: String? = null,
    val lastError: String? = null,
)

data class EpistolaReleasesDocument(
    val schemaVersion: Int = 0,
    val products: Map<String, ProductReleases> = emptyMap(),
)

data class ProductReleases(
    val stable: ReleaseRef? = null,
    val prerelease: ReleaseRef? = null,
    val support: SupportPolicy? = null,
)

/**
 * Product-level support policy. [minVersion] is the oldest still-supported release; installations
 * below it are flagged unsupported. [until] is the ISO date (`YYYY-MM-DD`) through which that floor
 * is supported — informational, surfaced to operators so they can plan an upgrade.
 */
data class SupportPolicy(
    val minVersion: String? = null,
    val until: String? = null,
)

/** A single published release the check compares against (the latest stable, or the latest pre-release). */
data class ReleaseRef(
    val version: String? = null,
    val releaseUrl: String? = null,
    val changelogUrl: String? = null,
    val publishedAt: Instant? = null,
)
