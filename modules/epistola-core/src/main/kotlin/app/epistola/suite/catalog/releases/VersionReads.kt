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
 * Reads one version of a catalog through the functions every moved resource type reads
 * (`catalog_version_resources` and the reads on it, ADR 0033), on a handle: the target those
 * functions are given ([CatalogReleaseTargeting.VersionTargetArguments]) is [selection] for the block
 * only, so a caller reading a dependency's release or another draft does not leak that choice into
 * the rest of its message. The one place, beside the mediator's targeting ([CatalogReleaseTargeting]),
 * that says which version a read means.
 */
object VersionReads {
    /** Runs [block] on [handle] reading [selection]; the meant version when it is null. */
    fun <R> at(handle: Handle, selection: VersionSelection?, block: (Handle) -> R): R = when (selection) {
        null -> block(handle)

        // A draft read on its own: no release read around it carries into it.
        is VersionSelection.Draft -> CatalogReleaseTargeting.withDraft(selection.versionId) { block(handle) }

        // A release read keeps the draft targeted around it, for the other catalogs.
        is VersionSelection.Release -> CatalogReleaseTargeting.withRelease(catalogIdOf(handle, selection.catalog), selection.version) { block(handle) }
    }

    /** The `catalog_id` of [catalog], or null when the tenant has none: then no release is read. */
    private fun catalogIdOf(handle: Handle, catalog: CatalogId): UUID? = handle.createQuery("SELECT ${catalogAt("t", "c")}")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .mapTo(UUID::class.java)
        .findOne()
        .orElse(null)
}
