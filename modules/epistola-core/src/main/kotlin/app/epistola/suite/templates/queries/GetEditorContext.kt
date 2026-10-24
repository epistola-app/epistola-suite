// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.dependencies.DraftDependencyViews
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilResolvers
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.templateAtAddress
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * All context needed to render the template editor.
 */
data class EditorContext(
    val templateName: String,
    val templateThemeKey: ThemeKey?,
    val templateThemeCatalogKey: CatalogKey?,
    /** The catalog of [templateThemeKey], by its address (ADR 0034, section 9). */
    val templateThemeCatalog: app.epistola.suite.common.ids.CatalogId? = null,
    val variantAttributes: Map<String, String>,
    /**
     * The variant's document with its stencil instances expanded from the stencils' working copies
     * (contract ADR 0006), as the author sees it. Saving it stores the reference form again.
     */
    val templateModel: TemplateDocument,
    val dataExamples: List<DataExample>,
    val dataModel: ObjectNode?,
    /** The variant's revision as read with [templateModel]: what saving the model names, so a save over someone else's is refused. */
    val variantRevision: Long = 1,
)

/**
 * Gets all context needed for the template editor in a single query.
 * This avoids multiple round-trips to the database.
 */
data class GetEditorContext(
    val variantId: VariantId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<EditorContext?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class GetEditorContextHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val stencilResolvers: StencilResolvers,
    private val dependencyViews: DraftDependencyViews,
) : QueryHandler<GetEditorContext, EditorContext?> {
    override fun handle(query: GetEditorContext): EditorContext? = jdbi.withHandle<EditorContext?, Exception> { handle ->
        val row = handle.createQuery(
            """
            SELECT
                dt.name as template_name,
                th.id AS theme_key,
                th_cat.catalog_key AS theme_catalog_key,
                th_cat.namespace AS theme_catalog_namespace,
                dt.data_model,
                dt.data_examples,
                tv.attributes as variant_attributes,
                tv.template_model as draft_template_model,
                tv.revision AS variant_revision
            FROM template_variants tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.version_id = tv.version_id AND dt.resource_id = tv.template_resource_id
            -- The theme is in the template's own version, or the current version of another catalog.
            LEFT JOIN current_themes th ON th.tenant_key = dt.tenant_key AND th.resource_id = dt.theme_resource_id
            LEFT JOIN catalogs th_cat ON th_cat.tenant_key = th.tenant_key AND th_cat.catalog_id = th.catalog_id
            WHERE tv.tenant_key = :tenantId AND tv.version_id = ${versionAt("tenantId", "catalogKey")}
              AND tv.template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
              AND tv.id = :variantId
            """,
        )
            .bind("templateId", query.variantId.templateKey)
            .bind("tenantId", query.variantId.tenantKey)
            .bindCatalog("catalogKey", query.variantId.catalogId)
            .bind("variantId", query.variantId.key)
            .mapToMap()
            .findOne()
            .orElse(null) ?: return@withHandle null

        // Deserialize data_examples from JSONB (comes as String or PGobject from mapToMap)
        val dataExamples: List<DataExample> = row["data_examples"]?.let { raw ->
            val json = raw.toString()
            if (json.isNotBlank() && json != "null") {
                try {
                    val typeRef = object : tools.jackson.core.type.TypeReference<List<DataExample>>() {}
                    objectMapper.readValue(json, typeRef)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } ?: emptyList()

        // Deserialize variant_attributes from JSONB (comes as PGobject from mapToMap)
        val variantAttributes: Map<String, String> = row["variant_attributes"]?.let { raw ->
            val json = raw.toString()
            if (json.isNotBlank() && json != "null") {
                try {
                    val typeRef = object : tools.jackson.core.type.TypeReference<Map<String, String>>() {}
                    objectMapper.readValue(json, typeRef)
                } catch (e: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
        } ?: emptyMap()

        // Deserialize draft_template_model from JSONB
        val templateModel = row["draft_template_model"]?.let { raw ->
            val json = raw.toString()
            if (json.isNotBlank()) {
                objectMapper.readValue(json, TemplateDocument::class.java)
            } else {
                null
            }
        } ?: error("Variant ${query.variantId} has no templateModel")

        EditorContext(
            templateName = row["template_name"] as String,
            templateThemeKey = (row["theme_key"] as String?)?.let(ThemeKey::of),
            templateThemeCatalogKey = (row["theme_catalog_key"] as String?)?.let(CatalogKey::of),
            templateThemeCatalog = (row["theme_catalog_key"] as String?)?.let { key ->
                app.epistola.suite.common.ids.CatalogId(row["theme_catalog_namespace"] as String, CatalogKey.of(key), query.variantId.tenantId)
            },
            variantAttributes = variantAttributes,
            variantRevision = (row["variant_revision"] as Number).toLong(),
            templateModel = stencilResolvers.expandWithWorkingCopies(query.variantId.catalogId, templateModel, dependencyViews.of(query.variantId.catalogId)),
            dataExamples = dataExamples,
            dataModel = row["data_model"]?.let { raw ->
                val json = raw.toString()
                if (json.isNotBlank() && json != "null") {
                    try {
                        objectMapper.readValue(json, ObjectNode::class.java)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            },
        )
    }
}
