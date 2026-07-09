package app.epistola.suite.versioncheck

import java.time.Instant

data class VersionCheckStatus(
    val checkedAt: Instant? = null,
    val currentVersion: String? = null,
    val metadataAvailable: Boolean = true,
    val latestVersion: String? = null,
    val channel: VersionCheckChannel? = null,
    val updateAvailable: Boolean = false,
    val releaseUrl: String? = null,
    val changelogUrl: String? = null,
    /** False when the running version is below the product's minimum supported version. */
    val supported: Boolean = true,
    /** The product's minimum supported version, when the metadata declares one. */
    val minSupportedVersion: String? = null,
    /** ISO date (`YYYY-MM-DD`) until which the minimum supported version is supported, if declared. */
    val supportedUntil: String? = null,
    val lastError: String? = null,
)

enum class VersionCheckChannel {
    STABLE,
    PRERELEASE,
}

data class EpistolaReleasesDocument(
    val schemaVersion: Int = 0,
    val products: Map<String, ProductReleases> = emptyMap(),
)

data class ProductReleases(
    val stable: ReleaseChannel? = null,
    val prerelease: ReleaseChannel? = null,
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

data class ReleaseChannel(
    val version: String? = null,
    val releaseUrl: String? = null,
    val changelogUrl: String? = null,
    val publishedAt: Instant? = null,
)
