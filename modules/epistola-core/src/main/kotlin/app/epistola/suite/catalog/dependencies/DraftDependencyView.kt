// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.CatalogReferences
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogId
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
    val ownCatalog: CatalogId,
    private val sources: Map<CatalogId, Source>,
    /** For each catalog it reads, what its references' aliases mean there: its declarations, or the pins of the release it is read from. */
    private val aliases: Map<CatalogId, Map<String, CatalogId>>,
    private val assembler: ReleaseContentAssembler?,
    /** What an alias means in a catalog read live, which no declaration of this draft covers. */
    private val live: ((CatalogId, String) -> CatalogId?)?,
) {
    /** One catalog's source. */
    sealed interface Source {
        /** A release, read from its retained content. */
        data class Release(val catalog: CatalogId, val version: String) : Source

        /** A draft, read live: its internal [versionId] and its public [key]. */
        data class Draft(val catalog: CatalogId, val versionId: UUID, val key: String) : Source
    }

    private val tenantKey: TenantKey get() = ownCatalog.tenantKey

    /**
     * The catalog a reference by [alias] means in content of [from] (ADR 0034, section 9): [from]
     * itself without an alias, else what [from]'s declarations, or the release it is read from, name
     * by it; null when nothing does.
     */
    fun resolve(from: CatalogId, alias: String?): CatalogId? = when {
        CatalogReferences.own(from, alias) -> from

        // A catalog nothing declares is read live: what the alias means there, else the tenant's one
        // catalog with that key, as before declarations existed.
        else -> aliases[from]?.get(alias) ?: live?.invoke(from, requireNotNull(alias))
    }

    /** Where [catalog] comes from, or null for the draft's own catalog and one nothing declares (live). */
    fun sourceOf(catalog: CatalogId): Source? = if (catalog == ownCatalog) null else sources[catalog]

    /** [catalog]'s release this draft reads, when it reads one. */
    fun releaseOf(catalog: CatalogId): Source.Release? = sourceOf(catalog) as? Source.Release

    /** [catalog]'s draft this draft reads, when it names one. */
    fun draftOf(catalog: CatalogId): Source.Draft? = sourceOf(catalog) as? Source.Draft

    /** A resource of the release [catalog] is read from, or null when it reads no release or that one lacks it. */
    fun released(catalog: CatalogId, type: String, key: String): CatalogResource? {
        val release = releaseOf(catalog) ?: return null
        return assembler?.readResource(release.catalog, release.version, type, key)
    }

    /** Bytes a retained release holds under [contentHash]. */
    fun releasedBinary(contentHash: String): ByteArray? = assembler?.readBinary(tenantKey, contentHash)

    companion object {
        /** A view that reads every other catalog live: for a render outside any draft's declarations. */
        fun live(ownCatalog: CatalogId, references: CatalogReferences? = null): DraftDependencyView = DraftDependencyView(ownCatalog, emptyMap(), emptyMap(), null, references?.let { r -> { from: CatalogId, alias: String -> r.resolve(from, alias) ?: r.uniqueByKey(from, alias) } })
    }
}

/** Builds [DraftDependencyView]s. */
@Component
class DraftDependencyViews(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
    private val assembler: ReleaseContentAssembler,
    private val references: CatalogReferences,
) {
    /**
     * The view of [catalog]'s current version: the draft the running message targets, else its next
     * version. A subscribed catalog has no draft, so it reads every other catalog live.
     */
    fun of(catalog: CatalogId): DraftDependencyView = jdbi.withHandle<DraftDependencyView, Exception> { handle ->
        val sources = LinkedHashMap<CatalogId, DraftDependencyView.Source>()
        val aliases = LinkedHashMap<CatalogId, Map<String, CatalogId>>()
        val releases = ArrayDeque<DraftDependencyView.Source.Release>()
        val own = store.declarations(handle, catalog)
        aliases[catalog] = own.associate { it.alias to it.catalogIn(catalog.tenantKey) }
        for (declaration in own) {
            val other = declaration.catalogIn(catalog.tenantKey)
            if (other == catalog) continue
            val draftKey = declaration.draftId
            val version = declaration.releaseVersion
            val source = when {
                draftKey != null -> store.draftOf(handle, catalog.tenantKey, declaration, draftKey)
                    ?.let { (versionId, _) -> DraftDependencyView.Source.Draft(other, versionId, draftKey) }

                version != null -> DraftDependencyView.Source.Release(other, version).also(releases::addLast)

                else -> null
            } ?: continue
            sources[other] = source
        }
        val visited = releases.toMutableSet()
        while (releases.isNotEmpty()) {
            val from = releases.removeFirst()
            val pins = store.releasePins(handle, from.catalog, from.version)
            aliases.putIfAbsent(from.catalog, pins.associate { it.alias to it.catalogIn(catalog.tenantKey) })
            for (pin in pins) {
                val version = pin.releaseVersion ?: continue
                val other = pin.catalogIn(catalog.tenantKey)
                if (other == catalog || other in sources) continue
                val release = DraftDependencyView.Source.Release(other, version)
                sources[other] = release
                if (visited.add(release)) releases.addLast(release)
            }
        }
        DraftDependencyView(catalog, sources, aliases, assembler) { from, alias -> references.resolve(from, alias) ?: references.uniqueByKey(from, alias) }
    }
}
