// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogReleaseIds
import app.epistola.catalog.protocol.ReleaseBase
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.releases.ReleaseParent
import java.util.UUID

/**
 * What a release says of itself in every manifest this suite writes for it (catalog wire v8): its id
 * (`release.releaseId`), the producer facts it carries (`release.metadata`, this suite's namespace
 * naming the rendering defaults it renders with, the installation that cut it and the releases merged
 * into it) and the release it was made from (`release.base`).
 * None of them is covered by the fingerprint, so describing a release never changes it.
 */
object ReleaseDescription {
    /** This suite's metadata namespace (contract: one reverse-DNS namespace per producer). */
    const val NAMESPACE: String = "app.epistola.suite"

    /** The member of [NAMESPACE] naming the rendering defaults version a release renders with. */
    const val RENDERING_DEFAULTS_VERSION: String = "renderingDefaultsVersion"

    /** The member of [NAMESPACE] naming the installation that cut the release (its installation id). */
    const val RELEASED_BY_INSTALLATION: String = "releasedByInstallation"

    /**
     * The member of [NAMESPACE] naming the release's merge parents (ADR 0032): the releases merged into
     * its draft before it was released, each `{"version": "1.0.1", "fingerprint": "v5:…"}`.
     */
    const val MERGED: String = "merged"

    /**
     * [received] (every namespace kept as it came, member for member) with this suite's namespace
     * naming [renderingDefaultsVersion] and, when given and [received] names none, the installation
     * that cut the release, and its [merged] parents when it names none; null when there is nothing
     * to say. A received release keeps the installation and the merge parents it names: it was cut
     * there, not where it is described again.
     */
    fun metadata(
        renderingDefaultsVersion: Int?,
        received: Map<String, Map<String, Any?>>? = null,
        releasedByInstallation: UUID? = null,
        merged: List<ReleaseParent> = emptyList(),
    ): Map<String, Map<String, Any?>>? {
        val others = received.orEmpty()
        var own = others[NAMESPACE].orEmpty()
        if (renderingDefaultsVersion != null) own = own + (RENDERING_DEFAULTS_VERSION to renderingDefaultsVersion)
        if (releasedByInstallation != null && own[RELEASED_BY_INSTALLATION] == null) own = own + (RELEASED_BY_INSTALLATION to releasedByInstallation.toString())
        if (merged.isNotEmpty() && own[MERGED] == null) {
            own = own + (MERGED to merged.map { parent -> listOfNotNull("version" to parent.version, parent.fingerprint?.let { "fingerprint" to it }).toMap() })
        }
        if (own.isEmpty()) return others.ifEmpty { null }
        return others + (NAMESPACE to own)
    }

    /** The installation [metadata] says cut the release; null when it names none, or not as an id. */
    fun releasedByInstallation(metadata: Map<String, Map<String, Any?>>?): UUID? = metadata?.get(NAMESPACE)?.get(RELEASED_BY_INSTALLATION)
        ?.toString()
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * The merge parents [metadata] names in this suite's namespace ([MERGED]), in its order: each a
     * `{version, fingerprint}` object, or a bare version; anything else is skipped. Empty when it
     * names none.
     */
    fun merged(metadata: Map<String, Map<String, Any?>>?): List<ReleaseParent> = (metadata?.get(NAMESPACE)?.get(MERGED) as? List<*>).orEmpty().mapNotNull { item ->
        when (item) {
            is String -> item.takeIf(String::isNotBlank)?.let { ReleaseParent(it, null) }
            is Map<*, *> -> (item["version"] as? String)?.takeIf(String::isNotBlank)?.let { ReleaseParent(it, item["fingerprint"] as? String) }
            else -> null
        }
    }.filter { it.version.length <= MAX_VERSION_LENGTH }.distinctBy { it.version }

    /**
     * The rendering defaults version [metadata] names in this suite's namespace: null when it names
     * none, [Unreadable] when it names one that is not a whole number.
     */
    fun renderingDefaultsVersion(metadata: Map<String, Map<String, Any?>>?): RenderingDefaultsClaim? {
        val value = metadata?.get(NAMESPACE)?.get(RENDERING_DEFAULTS_VERSION) ?: return null
        val number = (value as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() && it.toLong() in 0..Int.MAX_VALUE }
        return number?.let { RenderingDefaultsClaim.Version(it.toInt()) } ?: RenderingDefaultsClaim.Unreadable(value.toString())
    }

    /** What a release's metadata says of its rendering defaults. */
    sealed interface RenderingDefaultsClaim {
        data class Version(val version: Int) : RenderingDefaultsClaim

        data class Unreadable(val value: String) : RenderingDefaultsClaim
    }

    /**
     * The release made from [version] with [fingerprint], as `release.base` names it: null when there
     * is none, or when its fingerprint is not known in a form that names its algorithm (a release from
     * before 2.0), since a base by number alone could name another release.
     */
    fun base(version: String?, fingerprint: String?): ReleaseBase? {
        if (version == null || fingerprint == null || QualifiedCatalogFingerprint.parse(fingerprint) == null) return null
        return ReleaseBase(version, fingerprint)
    }

    /** [key] as a release id, when it is one ([CatalogReleaseIds]); a key that is not stays unsaid. */
    fun releaseId(key: String?): String? = key?.takeIf(CatalogReleaseIds::isValid)

    /**
     * [info] (as a release's snapshot recorded it) completed from what the catalog records of the
     * release: its id, its rendering defaults, and its base when the snapshot names none.
     */
    fun complete(info: ReleaseInfo, recorded: RecordedDescription): ReleaseInfo = info.copy(
        releaseId = releaseId(recorded.releaseId) ?: info.releaseId,
        metadata = metadata(recorded.renderingDefaultsVersion, info.metadata, recorded.releasedByInstallation, recorded.merged),
        base = info.base ?: base(recorded.baseVersion, recorded.baseFingerprint),
    )

    /** What `catalog_releases` records of a release that describes it. */
    data class RecordedDescription(
        val releaseId: String?,
        val renderingDefaultsVersion: Int?,
        val baseVersion: String?,
        val baseFingerprint: String?,
        /** The installation that cut it, as `catalog_releases.released_by_installation` records it. */
        val releasedByInstallation: UUID? = null,
        /** Its merge parents, as `catalog_release_merges` records them. */
        val merged: List<ReleaseParent> = emptyList(),
    )

    /** The longest release number `catalog_release_merges.merged_version` holds. */
    private const val MAX_VERSION_LENGTH = 50
}
