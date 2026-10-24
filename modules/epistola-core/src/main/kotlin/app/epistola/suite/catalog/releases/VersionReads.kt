// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import java.util.UUID

/** Which version of a catalog a read means, beside the one its message targets (ADR 0033). */
sealed interface VersionSelection {
    /** A draft, by its internal id. */
    data class Draft(val versionId: UUID) : VersionSelection

    /** A release of [catalog], by its number. */
    data class Release(val catalog: CatalogId, val version: String) : VersionSelection
}

/**
 * Reads one version of a catalog through the views every moved resource type reads
 * (`catalog_version_resources` and the views on it, ADR 0033), on a handle: the settings those views
 * read are set for the block and restored after it, so a caller reading a dependency's release or
 * another draft does not leak that choice into the rest of its transaction. The one place, beside the
 * mediator's targeting ([CatalogReleaseTargeting]), that says which version a read means.
 */
object VersionReads {
    private const val SETTINGS =
        "current_setting('${CatalogReleaseTargeting.SETTING}', TRUE) AS v, " +
            "current_setting('${CatalogReleaseTargeting.RELEASE_CATALOG_SETTING}', TRUE) AS rc, " +
            "current_setting('${CatalogReleaseTargeting.RELEASE_VERSION_SETTING}', TRUE) AS rv"

    /** Runs [block] on [handle] reading [selection]; the meant version when it is null. */
    fun <R> at(handle: Handle, selection: VersionSelection?, block: (Handle) -> R): R {
        if (selection == null) return block(handle)
        if (!handle.isInTransaction) return handle.inTransaction<R, Exception> { at(it, selection, block) }
        val previous = handle.createQuery("SELECT $SETTINGS").map { rs, _ -> Triple(rs.getString("v"), rs.getString("rc"), rs.getString("rv")) }.one()
        when (selection) {
            is VersionSelection.Draft -> set(handle, selection.versionId.toString(), "", "")

            is VersionSelection.Release -> handle.createQuery(
                """
                SELECT set_config('${CatalogReleaseTargeting.RELEASE_CATALOG_SETTING}', COALESCE(${catalogAt("t", "c")}::text, ''), TRUE) AS rc,
                       set_config('${CatalogReleaseTargeting.RELEASE_VERSION_SETTING}', :rv, TRUE) AS rv
                """,
            )
                .bind("t", selection.catalog.tenantKey)
                .bindCatalog("c", selection.catalog)
                .bind("rv", selection.version)
                .mapToMap()
                .one()
        }
        try {
            return block(handle)
        } finally {
            set(handle, previous.first.orEmpty(), previous.second.orEmpty(), previous.third.orEmpty())
        }
    }

    private fun set(handle: Handle, version: String, releaseCatalog: String, releaseVersion: String) {
        handle.createQuery(
            """
            SELECT set_config('${CatalogReleaseTargeting.SETTING}', :v, TRUE) AS v,
                   set_config('${CatalogReleaseTargeting.RELEASE_CATALOG_SETTING}', :rc, TRUE) AS rc,
                   set_config('${CatalogReleaseTargeting.RELEASE_VERSION_SETTING}', :rv, TRUE) AS rv
            """,
        )
            .bind("v", version)
            .bind("rc", releaseCatalog)
            .bind("rv", releaseVersion)
            .mapToMap()
            .one()
    }
}
