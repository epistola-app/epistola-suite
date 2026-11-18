// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
    /**
     * False when the running version's line has ended (as the website decided it), or, for an older
     * document without lines, when it is below the minimum supported version.
     */
    val supported: Boolean = true,
    /**
     * True when the running version's line is ending (it has an end date that has not passed), or,
     * for an older document without lines, when [supportedUntil] falls within the warning window.
     */
    val supportEndingSoon: Boolean = false,
    /** The product's minimum supported version, when the metadata declares one. */
    val minSupportedVersion: String? = null,
    /**
     * ISO date (`YYYY-MM-DD`): the last supported day of the running version's line, when it is
     * ending or has ended. For an older document without lines, the floor line's date, and null for
     * a running version on a newer line: that date is not its own.
     */
    val supportedUntil: String? = null,
    /**
     * Published security advisories that apply to [currentVersion], most severe first. Null when
     * they are unknown (not published by the endpoint yet, or never fetched); empty when none apply.
     */
    val advisories: List<ApplicableAdvisory>? = null,
    val lastError: String? = null,
)

/** A published advisory that applies to the running version, already cleaned for display. */
data class ApplicableAdvisory(
    val id: String,
    /** `critical`, `high`, `medium`, `low` or `unknown`. */
    val severity: String,
    val summary: String? = null,
    /** The first release with the fix; null while none is released. */
    val fixedIn: String? = null,
    /** Always an `https:` URL, or null. */
    val url: String? = null,
)

/**
 * The releases document epistola.app serves (schema: https://epistola.app/releases.schema.json).
 * Unknown fields are ignored: the website only ever adds fields.
 */
data class EpistolaReleasesDocument(
    val schemaVersion: Int = 0,
    /** When the website computed the document; line statuses hold for that day. */
    val generatedAt: Instant? = null,
    val products: Map<String, ProductReleases> = emptyMap(),
)

data class ProductReleases(
    val stable: ReleaseRef? = null,
    /** Legacy: the newest pre-release, even when older than [stable]. New code reads [next]. */
    val prerelease: ReleaseRef? = null,
    /** The newest pre-release, only while it is newer than [stable]. */
    val next: ReleaseRef? = null,
    /** Every release line, newest first, with the support status the website decided. */
    val lines: List<ReleaseLine>? = null,
    /** Legacy: the oldest supported line, as a floor. New code reads [lines]. */
    val support: SupportPolicy? = null,
    /** Published security advisories. Null (absent) means unknown; an empty list means none. */
    val advisories: List<Advisory>? = null,
)

/** A published security advisory, as epistola.app serves it (taken from the OSV records). */
data class Advisory(
    val id: String? = null,
    val aliases: List<String> = emptyList(),
    val severity: String? = null,
    val summary: String? = null,
    val published: String? = null,
    /** SemVer ranges; the running version is affected when it falls in any of them. */
    val affected: List<AffectedRange> = emptyList(),
    val fixedIn: String? = null,
    val url: String? = null,
)

/**
 * An OSV SEMVER range: affected from [introduced] (inclusive) to [fixed] (exclusive) or
 * [lastAffected] (inclusive); open-ended when neither is set.
 */
data class AffectedRange(
    val introduced: String? = null,
    val fixed: String? = null,
    val lastAffected: String? = null,
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

/** One `major.minor` release line. */
data class ReleaseLine(
    /** `major.minor`, e.g. `2.0`. */
    val line: String? = null,
    /** The line's newest final release. */
    val latest: ReleaseRef? = null,
    /** The line's last supported day (UTC, inclusive), or null while it has no successor. */
    val supportEnds: String? = null,
    /** `supported`, `ending` or `ended`; more values may be added. */
    val status: String? = null,
)

/**
 * The Suite's entry from the last successful fetch, kept so the status is evaluated whenever it is
 * read, for the version that is running then: an upgrade shows its own status at once, and a later
 * failed fetch keeps this entry rather than an old verdict.
 */
data class StoredReleases(
    val fetchedAt: Instant? = null,
    val generatedAt: Instant? = null,
    val product: ProductReleases? = null,
)

/** A single published release the check compares against (the latest stable, or the latest pre-release). */
data class ReleaseRef(
    val version: String? = null,
    val releaseUrl: String? = null,
    val changelogUrl: String? = null,
    val publishedAt: Instant? = null,
)
