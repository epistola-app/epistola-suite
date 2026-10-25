// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.catalog.CatalogReferences
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.dependencies.DraftDependencyView
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.stencils.model.StencilInstances
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Where a stencil instance finds its stencil when it is not rendered from a release: the working
 * copies of the tenant's stencils, for the editor, previews of the working copy, and checks.
 *
 * Each resolver reads a stencil once and remembers it, so expanding a document with many instances
 * costs one query per distinct stencil. A resolver is meant for one operation; it does not see
 * changes made after it read a stencil.
 */
@Component
class StencilResolvers(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val references: CatalogReferences,
) {
    /**
     * [document], owned by [catalog], with its stencil instances expanded from the working copies,
     * for rendering outside a release. An instance that does not fit renders as the expansion leaves
     * it, so a preview stays usable while the author fixes it; publishing refuses it.
     */
    fun expandWithWorkingCopies(catalog: CatalogId, document: TemplateDocument, dependencies: DraftDependencyView? = null): TemplateDocument = StencilInstances.expand(document, catalog.address, workingCopies(dependencies ?: DraftDependencyView.live(catalog, references))).document

    /**
     * Stencils as [dependencies] says a draft reads them: its own catalog's in the draft, another
     * catalog's from the release it pins or the draft it names, each reference resolved from the
     * catalog of the content it is in ([DraftDependencyView.resolve]). Catalogs are named by address.
     */
    fun workingCopies(dependencies: DraftDependencyView): StencilInstances.Resolver {
        val cache = mutableMapOf<Pair<String, CatalogId>, StencilInstances.Content?>()
        val tenantId = dependencies.ownCatalog.tenantId
        return StencilInstances.Resolver { stencilKey, alias, from ->
            val catalog = dependencies.resolve(CatalogId.of(from, tenantId), alias) ?: return@Resolver null
            cache.getOrPut(stencilKey to catalog) { load(catalog, stencilKey, dependencies) }
        }
    }

    private fun load(catalog: CatalogId, stencilKey: String, dependencies: DraftDependencyView): StencilInstances.Content? {
        dependencies.releaseOf(catalog)?.let {
            return (dependencies.released(catalog, "stencil", stencilKey) as? StencilResource)
                ?.let { stencil -> StencilInstances.Content(stencil.content, stencil.parameterSchema, stencil.readsTemplateData, catalog.address) }
        }
        val draft = dependencies.draftOf(catalog)
        return jdbi.withHandle<StencilInstances.Content?, Exception> { handle -> load(handle, catalog, stencilKey, draft?.versionId) }
    }

    // A draft other than the current one is read through the views as that draft (ADR 0033).
    private fun load(handle: Handle, catalog: CatalogId, stencilKey: String, versionId: UUID?): StencilInstances.Content? = app.epistola.suite.catalog.releases.VersionReads.at(
        handle,
        versionId?.let { app.epistola.suite.catalog.releases.VersionSelection.Draft(it) },
    ) { at -> read(at, catalog, stencilKey) }

    private fun read(handle: Handle, catalog: CatalogId, stencilKey: String): StencilInstances.Content? = handle.createQuery(
        """
            SELECT s.content::text, s.parameter_schema::text, s.reads_template_data
            FROM current_stencils s
            WHERE s.tenant_key = :tenantKey AND s.catalog_id = epistola_catalog_id(:tenantKey, :catalogKey_ns, :catalogKey) AND s.id = :stencilKey
            """,
    )
        .bind("tenantKey", catalog.tenantKey)
        .bindCatalog("catalogKey", catalog)
        .bind("stencilKey", stencilKey)
        .map { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            StencilInstances.Content(
                // Stored in its released form; a draft renders with its own-catalog references
                // qualified, as it always has.
                document = objectMapper.readTree(rs.getString(1))
                    .also { app.epistola.suite.catalog.graph.ResourceReferenceSites.qualifyRelative(it, catalog.key.value) }
                    .let { objectMapper.treeToValue(it, TemplateDocument::class.java) },
                parameterSchema = rs.getString(2)?.let { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> },
                readsTemplateData = rs.getBoolean(3),
                catalog = catalog.address,
            )
        }
        .findOne()
        .orElse(null)
}
