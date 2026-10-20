// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Where a draft reads another catalog's resources from: what its declarations say, so a draft
 * renders with what its release would be cut with (contract ADR 0003).
 *
 * - A declaration pinning a release reads that release's retained content.
 * - A declaration naming a draft reads that draft, live.
 * - A catalog only a pinned release depends on comes from the release that release pinned, nearest
 *   first, as rendering a release does.
 * - A catalog nothing declares is read live from its current version, as before declarations
 *   existed; writes declare what they reference, so this is a catalog upgraded from 1.x or a
 *   reference to one the tenant lacks.
 *
 * The catalog the draft belongs to is never in it: its own resources resolve in the draft.
 */
class DraftDependencyView internal constructor(
    val ownCatalog: CatalogKey,
    private val sources: Map<CatalogKey, Source>,
    private val assembler: ReleaseContentAssembler?,
    private val tenantKey: TenantKey,
) {
    /** One catalog's source. */
    sealed interface Source {
        /** A release, read from its retained content. */
        data class Release(val catalogKey: CatalogKey, val version: String) : Source

        /** A draft, read live: its internal [versionId] and its public [key]. */
        data class Draft(val catalogKey: CatalogKey, val versionId: UUID, val key: String) : Source
    }

    /** Where [catalog] comes from, or null for the draft's own catalog and one nothing declares (live). */
    fun sourceOf(catalog: CatalogKey): Source? = if (catalog == ownCatalog) null else sources[catalog]

    /** [catalog]'s release this draft reads, when it reads one. */
    fun releaseOf(catalog: CatalogKey): Source.Release? = sourceOf(catalog) as? Source.Release

    /** [catalog]'s draft this draft reads, when it names one. */
    fun draftOf(catalog: CatalogKey): Source.Draft? = sourceOf(catalog) as? Source.Draft

    /** A resource of the release [catalog] is read from, or null when it reads no release or that one lacks it. */
    fun released(catalog: CatalogKey, type: String, key: String): CatalogResource? {
        val release = releaseOf(catalog) ?: return null
        return assembler?.readResource(tenantKey, release.catalogKey, release.version, type, key)
    }

    /** Bytes a retained release holds under [contentHash]. */
    fun releasedBinary(contentHash: String): ByteArray? = assembler?.readBinary(tenantKey, contentHash)

    companion object {
        /** A view that reads every other catalog live: for a render outside any draft's declarations. */
        fun live(tenantKey: TenantKey, ownCatalog: CatalogKey): DraftDependencyView = DraftDependencyView(ownCatalog, emptyMap(), null, tenantKey)
    }
}

/** Builds [DraftDependencyView]s. */
@Component
class DraftDependencyViews(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
    private val assembler: ReleaseContentAssembler,
) {
    /**
     * The view of [catalogKey]'s current version: the draft the running message targets, else its
     * next version. A subscribed catalog has no draft, so it reads every other catalog live.
     */
    fun of(tenantKey: TenantKey, catalogKey: CatalogKey): DraftDependencyView = jdbi.withHandle<DraftDependencyView, Exception> { handle ->
        val sources = LinkedHashMap<CatalogKey, DraftDependencyView.Source>()
        val releases = ArrayDeque<DraftDependencyView.Source.Release>()
        for (declaration in store.declarations(handle, tenantKey, catalogKey)) {
            // A dependency's key is its key in this tenant: a declaration names a catalog the tenant has.
            val other = CatalogKey.of(declaration.catalogKey)
            if (other == catalogKey) continue
            val draftKey = declaration.draftId
            val version = declaration.releaseVersion
            val source = when {
                draftKey != null -> store.draftOf(handle, tenantKey, declaration, draftKey)
                    ?.let { (versionId, _) -> DraftDependencyView.Source.Draft(other, versionId, draftKey) }

                version != null -> DraftDependencyView.Source.Release(other, version).also(releases::addLast)

                else -> null
            } ?: continue
            sources[other] = source
        }
        val visited = releases.toMutableSet()
        while (releases.isNotEmpty()) {
            val from = releases.removeFirst()
            for (pin in store.releasePins(handle, tenantKey, from.catalogKey, from.version)) {
                val version = pin.releaseVersion ?: continue
                val other = CatalogKey.of(pin.catalogKey)
                if (other == catalogKey || other in sources) continue
                val release = DraftDependencyView.Source.Release(other, version)
                sources[other] = release
                if (visited.add(release)) releases.addLast(release)
            }
        }
        DraftDependencyView(catalogKey, sources, assembler, tenantKey)
    }
}
