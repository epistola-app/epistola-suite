// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.Catalog

/**
 * How a release came to be in a tenant (`catalog_versions.released_via`): its provenance, with who
 * did it (`released_by`) and when (`released_at`).
 *
 * Descriptive only. A release is a release however it arrived (ADR 0036): nothing decides by its
 * channel, it is shown to people (`ReleaseProvenanceUsageTest` holds that only the code recording and
 * showing it names this type).
 */
enum class ReleaseChannel(val code: String) {
    /** Released here, from a draft. */
    RELEASED("released"),

    /** A catalog archive somebody uploaded in the UI. */
    UPLOAD("upload"),

    /** A catalog archive imported through the REST API. */
    API("api"),

    /** Installed from an OCI registry. */
    REGISTRY("registry"),

    /** Installed from Epistola Exchange. */
    EXCHANGE("exchange"),

    /** Received from the catalog's source: a manifest URL. */
    SOURCE("source"),

    /** A catalog Epistola ships (a `classpath:` source): the system and demo catalogs. */
    BUNDLED("bundled"),

    /** The release a subscribed 1.x catalog had installed, recorded on the upgrade to 2.0. */
    UPGRADE_FROM_1X("upgrade-1x"),

    /** Restored from a tenant snapshot. */
    SNAPSHOT("snapshot"),
    ;

    companion object {
        fun of(code: String?): ReleaseChannel? = entries.firstOrNull { it.code == code }
    }
}

/** How a release arrives: its [via] channel and, where there is one, the place it came [from]. */
data class ReleaseProvenance(val via: ReleaseChannel, val from: String? = null) {
    companion object {
        /** The longest [from] a release keeps; an uploaded file's name is the caller's to choose. */
        const val MAX_FROM: Int = 255

        /** Arrived through [via] as the file [fileName], whose name is kept up to [MAX_FROM] characters. */
        fun ofFile(via: ReleaseChannel, fileName: String?): ReleaseProvenance = ReleaseProvenance(via, fileName?.takeIf { it.isNotBlank() }?.take(MAX_FROM))

        /** Received from [catalog]'s own source: bundled for a catalog Epistola supplies, else its URL. */
        fun ofSource(catalog: Catalog): ReleaseProvenance = ofSource(catalog.sourceUrl)

        /** Received from source [sourceUrl]: bundled for a `classpath:` one. */
        fun ofSource(sourceUrl: String?): ReleaseProvenance = if (sourceUrl?.startsWith(Catalog.CLASSPATH_SOURCE) == true) ReleaseProvenance(ReleaseChannel.BUNDLED, sourceUrl) else ReleaseProvenance(ReleaseChannel.SOURCE, sourceUrl)
    }
}
