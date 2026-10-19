// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.catalog.dependencies.DraftDependencyView
import app.epistola.suite.catalog.versionAt
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
) {
    /**
     * [document], owned by [catalogKey], with its stencil instances expanded from the working copies,
     * for rendering outside a release. An instance that does not fit renders as the expansion leaves
     * it, so a preview stays usable while the author fixes it; publishing refuses it.
     */
    fun expandWithWorkingCopies(tenantKey: TenantKey, catalogKey: CatalogKey, document: TemplateDocument, dependencies: DraftDependencyView? = null): TemplateDocument = StencilInstances.expand(document, catalogKey.value, workingCopies(tenantKey, dependencies)).document

    /**
     * Stencils as [dependencies] says a draft reads them: its own catalog's in the draft, another
     * catalog's from the release it pins or the draft it names; without a view, every catalog's
     * current version.
     */
    fun workingCopies(tenantKey: TenantKey, dependencies: DraftDependencyView? = null): StencilInstances.Resolver {
        val cache = mutableMapOf<Pair<String, String>, StencilInstances.Content?>()
        return StencilInstances.Resolver { stencilKey, catalogKey ->
            cache.getOrPut(stencilKey to catalogKey) { load(tenantKey, stencilKey, catalogKey, dependencies) }
        }
    }

    private fun load(tenantKey: TenantKey, stencilKey: String, catalogKey: String, dependencies: DraftDependencyView?): StencilInstances.Content? {
        val catalog = CatalogKey.of(catalogKey)
        dependencies?.releaseOf(catalog)?.let {
            return (dependencies.released(catalog, "stencil", stencilKey) as? StencilResource)
                ?.let { stencil -> StencilInstances.Content(stencil.content, stencil.parameterSchema, stencil.readsTemplateData) }
        }
        val draft = dependencies?.draftOf(catalog)
        return jdbi.withHandle<StencilInstances.Content?, Exception> { handle -> load(handle, tenantKey, stencilKey, catalogKey, draft?.versionId) }
    }

    private fun load(handle: Handle, tenantKey: TenantKey, stencilKey: String, catalogKey: String, versionId: UUID?): StencilInstances.Content? = handle.createQuery(
        """
            SELECT s.content::text, s.parameter_schema::text, s.reads_template_data
            FROM stencils s JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.catalog_id = s.catalog_id
            WHERE s.tenant_key = :tenantKey AND c.catalog_key = :catalogKey AND s.id = :stencilKey
              AND s.version_id = ${if (versionId != null) ":versionId" else versionAt("tenantKey", "catalogKey")}
            """,
    )
        .bind("tenantKey", tenantKey)
        .bind("catalogKey", catalogKey)
        .bind("stencilKey", stencilKey)
        .also { query -> versionId?.let { query.bind("versionId", it) } }
        .map { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            StencilInstances.Content(
                document = objectMapper.readValue(rs.getString(1), TemplateDocument::class.java),
                parameterSchema = rs.getString(2)?.let { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> },
                readsTemplateData = rs.getBoolean(3),
            )
        }
        .findOne()
        .orElse(null)
}
