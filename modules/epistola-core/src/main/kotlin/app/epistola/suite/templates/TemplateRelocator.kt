// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.relocation.JsonRewrite
import app.epistola.suite.catalog.relocation.ResourceRelocationPlan
import app.epistola.suite.catalog.relocation.ResourceRelocator
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Relocates templates (ADR 0039): a template moves with its models stored again in the destination's
 * form; a variant's model or attributes are rewritten as the plan says; and a template follows a
 * theme it names that moves.
 */
@Component
class TemplateRelocator(
    private val templates: TemplateStore,
    private val objectMapper: ObjectMapper,
) : ResourceRelocator {
    override val type = TemplateStore.TEMPLATE

    override fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean = templates.move(handle, source, key, target, targetKey)

    override fun rewrite(handle: Handle, tenantKey: TenantKey, rewrite: JsonRewrite): Int = when (rewrite) {
        is JsonRewrite.VariantModel -> rewriteVariant(handle, tenantKey, rewrite.catalogKey, rewrite.ownerKey) { template, catalog ->
            val variant = template.variant(rewrite.variantKey) ?: return@rewriteVariant null
            val stored = templates.model(handle, tenantKey, variant) ?: return@rewriteVariant null
            // The plan saw the stored model.
            if (objectMapper.valueToTree<JsonNode>(stored) != objectMapper.readTree(rewrite.expected)) return@rewriteVariant null
            val replacement = objectMapper.readValue(rewrite.replacement, TemplateDocument::class.java)
            // Stored in its released form (ADR 0033), as every model is.
            template.withVariant(rewrite.variantKey) { it.copy(model = templates.storedModel(replacement, catalog.key.value)) }
        }

        is JsonRewrite.VariantAttributes -> rewriteVariant(handle, tenantKey, rewrite.catalogKey, rewrite.ownerKey) { template, _ ->
            val variant = template.variant(rewrite.variantKey) ?: return@rewriteVariant null
            if (objectMapper.valueToTree<JsonNode>(variant.attributes) != objectMapper.readTree(rewrite.expected)) return@rewriteVariant null
            template.withVariant(rewrite.variantKey) { it.copy(attributes = objectMapper.readValue(rewrite.replacement, STRINGS)) }
        }

        else -> 0
    }

    /**
     * The templates of every draft that use theme [resourceId], which is about to move: a template names
     * its theme by alias and key (ADR 0033), where it used to name its identity and so followed it. Each
     * is pointed at the theme's destination, the alias relative to the template's own catalog.
     */
    override fun beforeMove(handle: Handle, tenantKey: TenantKey, moved: ResourceRelocationPlan, resourceId: UUID) {
        if (moved.source.type != CatalogResourceType.THEME) return
        val users = handle.createQuery(
            """
            SELECT t.id, c.namespace || '/' || c.catalog_key AS catalog_address, t.updated_by, t.revision
            FROM current_templates t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
            WHERE t.tenant_key = :t AND t.theme_resource_id = :theme AND t.version_id IS NOT NULL
            """,
        )
            .bind("t", tenantKey)
            .bind("theme", resourceId)
            .map { rs, _ -> listOf(rs.getString("id"), rs.getString("catalog_address")) to rs.getObject("updated_by", UUID::class.java) }
            .list()
        val target = moved.target.catalogIn(tenantKey)
        for ((address, author) in users) {
            val catalog = CatalogId.of(address[1], TenantId(tenantKey))
            val id = TemplateId(TemplateKey.of(address[0]), catalog)
            val loaded = templates.get(handle, id) ?: continue
            val following = loaded.template.copy(themeKey = moved.target.key, themeCatalogKey = target.key.value.takeUnless { target == catalog })
            templates.save(handle, catalog, loaded, following, author)
        }
    }

    /**
     * Template [ownerKey] of the catalog at [catalogAddress] changed by [change], when the change still
     * applies to what the plan saw; 1 when it did, else 0. A rewrite follows a move; it is not the
     * user's edit, so whoever edited it last stays.
     */
    private fun rewriteVariant(
        handle: Handle,
        tenantKey: TenantKey,
        catalogAddress: String,
        ownerKey: String,
        change: (StoredTemplate, CatalogId) -> StoredTemplate?,
    ): Int {
        val catalog = CatalogId.of(catalogAddress, TenantId(tenantKey))
        val id = TemplateId(TemplateKey.of(ownerKey), catalog)
        val loaded = templates.get(handle, id) ?: return 0
        val changed = change(loaded.template, catalog) ?: return 0
        val author = handle.createQuery("SELECT updated_by FROM current_templates WHERE tenant_key = :t AND resource_id = :id")
            .bind("t", tenantKey).bind("id", loaded.resourceId)
            .mapTo(UUID::class.java).findOne().orElse(null)
        return if (templates.save(handle, catalog, loaded, changed, author) != null) 1 else 0
    }

    private companion object {
        val STRINGS = object : tools.jackson.core.type.TypeReference<Map<String, String>>() {}
    }
}
