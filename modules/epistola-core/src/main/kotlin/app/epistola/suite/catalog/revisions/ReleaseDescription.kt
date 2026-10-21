// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogReleaseIds
import app.epistola.catalog.protocol.ReleaseBase
import app.epistola.catalog.protocol.ReleaseInfo

/**
 * What a release says of itself in every manifest this suite writes for it (catalog wire v8): its id
 * (`release.releaseId`), the producer facts it carries (`release.metadata`, this suite's namespace
 * naming the rendering defaults it renders with) and the release it was made from (`release.base`).
 * None of them is covered by the fingerprint, so describing a release never changes it.
 */
object ReleaseDescription {
    /** This suite's metadata namespace (contract: one reverse-DNS namespace per producer). */
    const val NAMESPACE: String = "app.epistola.suite"

    /** The member of [NAMESPACE] naming the rendering defaults version a release renders with. */
    const val RENDERING_DEFAULTS_VERSION: String = "renderingDefaultsVersion"

    /**
     * [received] (every namespace kept as it came, member for member) with this suite's namespace
     * naming [renderingDefaultsVersion]; null when there is nothing to say.
     */
    fun metadata(renderingDefaultsVersion: Int?, received: Map<String, Map<String, Any?>>? = null): Map<String, Map<String, Any?>>? {
        val others = received.orEmpty()
        if (renderingDefaultsVersion == null) return others.ifEmpty { null }
        val own = others[NAMESPACE].orEmpty() + (RENDERING_DEFAULTS_VERSION to renderingDefaultsVersion)
        return others + (NAMESPACE to own)
    }

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
        metadata = metadata(recorded.renderingDefaultsVersion, info.metadata),
        base = info.base ?: base(recorded.baseVersion, recorded.baseFingerprint),
    )

    /** What `catalog_releases` records of a release that describes it. */
    data class RecordedDescription(
        val releaseId: String?,
        val renderingDefaultsVersion: Int?,
        val baseVersion: String?,
        val baseFingerprint: String?,
    )
}
