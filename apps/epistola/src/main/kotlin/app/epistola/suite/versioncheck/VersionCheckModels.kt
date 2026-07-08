package app.epistola.suite.versioncheck

import java.time.Instant

data class VersionCheckStatus(
    val checkedAt: Instant? = null,
    val currentVersion: String? = null,
    val latestVersion: String? = null,
    val channel: VersionCheckChannel? = null,
    val updateAvailable: Boolean = false,
    val releaseUrl: String? = null,
    val changelogUrl: String? = null,
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
)

data class ReleaseChannel(
    val version: String? = null,
    val releaseUrl: String? = null,
    val changelogUrl: String? = null,
    val publishedAt: Instant? = null,
)
