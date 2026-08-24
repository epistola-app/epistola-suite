// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** A resource currently installed under a catalog. */
data class InstalledResource(val type: String, val slug: String)

/** A resource removed from a catalog during a stale-prune. */
data class RemovedResource(val type: String, val slug: String)

/**
 * Shared upgrade analysis — installed-resource discovery, stale computation,
 * cross-catalog conflict detection and stale removal — used by
 * [UpgradeCatalog][app.epistola.suite.catalog.commands.UpgradeCatalog] (URL
 * source), the SUBSCRIBED ZIP-import path
 * ([ImportCatalogZip][app.epistola.suite.catalog.commands.ImportCatalogZip])
 * and [PreviewCatalogUpgrade][app.epistola.suite.catalog.queries.PreviewCatalogUpgrade]
 * (so the same conflicts surface up front, not only at apply). One definition
 * of "what is stale and what removing it would break".
 */
@Component
class CatalogUpgradeAnalyzer(
    private val jdbi: Jdbi,
) {

    private companion object {
        val STALE_PRUNED_RESOURCE_TYPES = RESOURCE_INSTALL_ORDER.keys
        const val FONT_REFERENCE_JSON_PATH = "\$.** ? (@.slug == \$slug && @.catalogKey == \$catalogKey)"
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Every resource installed under `(tenant, catalog)`, including resource types not subject to stale pruning. */
    fun installedResources(tenantKey: TenantKey, catalogKey: CatalogKey): Set<InstalledResource> = jdbi.withHandle<Set<InstalledResource>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT 'template' AS type, id::text AS slug FROM document_templates WHERE tenant_key = :t AND catalog_key = :c
            UNION ALL SELECT 'theme', id::text FROM themes WHERE tenant_key = :t AND catalog_key = :c
            UNION ALL SELECT 'stencil', id::text FROM stencils WHERE tenant_key = :t AND catalog_key = :c
            UNION ALL SELECT 'attribute', id::text FROM variant_attribute_definitions WHERE tenant_key = :t AND catalog_key = :c
            UNION ALL SELECT 'asset', id::text FROM assets WHERE tenant_key = :t AND catalog_key = :c
            UNION ALL SELECT 'codeList', slug::text FROM code_lists WHERE tenant_key = :t AND catalog_key = :c
            UNION ALL SELECT 'font', slug::text FROM fonts WHERE tenant_key = :t AND catalog_key = :c
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ -> InstalledResource(rs.getString("type"), rs.getString("slug")) }
            .set()
    }

    /** Resources handled by the upgrade analyzer, grouped by type. */
    fun installedByType(tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, List<InstalledResource>> = installedResources(tenantKey, catalogKey)
        .filter { it.type in STALE_PRUNED_RESOURCE_TYPES }
        .groupBy { it.type }

    /** Installed resources whose `(type, slug)` is no longer in the manifest. */
    fun computeStale(
        installedByType: Map<String, List<InstalledResource>>,
        manifestSlugs: Map<String, List<String>>,
    ): List<InstalledResource> {
        val stale = mutableListOf<InstalledResource>()
        for ((type, installed) in installedByType) {
            val inManifest = manifestSlugs[type]?.toSet() ?: emptySet()
            installed.filter { it.slug !in inManifest }.forEach { stale.add(it) }
        }
        return stale
    }

    /**
     * Human-readable cross-catalog conflicts for the given stale resources
     * (a removed resource still referenced from another catalog). Empty = safe.
     */
    fun findConflicts(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        staleResources: List<InstalledResource>,
    ): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        val conflicts = mutableListOf<String>()
        for (resource in staleResources) {
            when (resource.type) {
                "theme" -> findThemeConflicts(handle, tenantKey, catalogKey, resource.slug, conflicts)
                "stencil" -> findStencilConflicts(handle, tenantKey, catalogKey, resource.slug, conflicts)
                "template" -> findTemplateConflicts(handle, tenantKey, catalogKey, resource.slug, conflicts)
                "attribute" -> findAttributeConflicts(handle, tenantKey, catalogKey, resource.slug, conflicts)
                "codeList" -> findCodeListConflicts(handle, tenantKey, catalogKey, resource.slug, conflicts)
                "font" -> findFontConflicts(handle, tenantKey, catalogKey, resource.slug, conflicts)
            }
        }
        conflicts
    }

    private fun findThemeConflicts(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, slug: String, conflicts: MutableList<String>) {
        handle.createQuery(
            """
            SELECT name, catalog_key FROM document_templates
            WHERE tenant_key = :t AND theme_catalog_key = :c AND theme_key = :slug AND catalog_key != :c
            """,
        ).bind("t", tenantKey).bind("c", catalogKey).bind("slug", slug)
            .map { rs, _ -> "Theme '$slug' is used by template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")})" }
            .list().let { conflicts.addAll(it) }

        handle.createQuery(
            "SELECT id FROM tenants WHERE id = :t AND default_theme_catalog_key = :c AND default_theme_key = :slug",
        ).bind("t", tenantKey).bind("c", catalogKey).bind("slug", slug)
            .mapTo(String::class.java).findOne().ifPresent {
                conflicts.add("Theme '$slug' is the tenant default theme")
            }
    }

    private fun findStencilConflicts(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, slug: String, conflicts: MutableList<String>) {
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, tv.catalog_key
            FROM template_versions tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            WHERE tv.tenant_key = :t AND tv.catalog_key != :c
              AND tv.status IN ('draft', 'published')
              AND n.value ->> 'type' = 'stencil'
              AND n.value -> 'props' ->> 'catalogKey' = :cStr
              AND n.value -> 'props' ->> 'stencilId' = :slug
            """,
        ).bind("t", tenantKey).bind("c", catalogKey).bind("cStr", catalogKey.value).bind("slug", slug)
            .map { rs, _ -> "Stencil '$slug' is used by template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")})" }
            .list().let { conflicts.addAll(it) }
    }

    private fun findTemplateConflicts(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, slug: String, conflicts: MutableList<String>) {
        val activationCount = handle.createQuery(
            """
            SELECT COUNT(*) FROM environment_activations
            WHERE tenant_key = :t AND catalog_key = :c AND template_key = :slug
            """,
        ).bind("t", tenantKey).bind("c", catalogKey).bind("slug", slug)
            .mapTo(Long::class.java).one()

        if (activationCount > 0) {
            conflicts.add("Template '$slug' has $activationCount environment activation(s) — removing it would break document generation")
        }
    }

    private fun findAttributeConflicts(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, slug: String, conflicts: MutableList<String>) {
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, v.catalog_key
            FROM template_variants v
            JOIN document_templates dt ON dt.tenant_key = v.tenant_key AND dt.catalog_key = v.catalog_key AND dt.id = v.template_key
            WHERE v.tenant_key = :t AND v.catalog_key != :c
              AND jsonb_exists(v.attributes::jsonb, :slug)
            """,
        ).bind("t", tenantKey).bind("c", catalogKey).bind("slug", slug)
            .map { rs, _ -> "Attribute '$slug' is used by template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")})" }
            .list().let { conflicts.addAll(it) }
    }

    private fun findCodeListConflicts(
        handle: Handle,
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        slug: String,
        conflicts: MutableList<String>,
    ) {
        handle.createQuery(
            """
            SELECT display_name, catalog_key
            FROM variant_attribute_definitions
            WHERE tenant_key = :t
              AND catalog_key != :c
              AND code_list_catalog_key = :c
              AND code_list_slug = :slug
            """,
        ).bind("t", tenantKey).bind("c", catalogKey).bind("slug", slug)
            .map { rs, _ ->
                "Code list '$slug' is used by attribute '${rs.getString("display_name")}' (catalog: ${rs.getString("catalog_key")})"
            }
            .list()
            .let { conflicts.addAll(it) }
    }

    private fun findFontConflicts(
        handle: Handle,
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        slug: String,
        conflicts: MutableList<String>,
    ) {
        val vars = """{"slug":"$slug","catalogKey":"${catalogKey.value}"}"""

        handle.createQuery(
            """
            SELECT name, catalog_key
            FROM themes
            WHERE tenant_key = :t
              AND (
                jsonb_path_exists(document_styles, CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
                OR jsonb_path_exists(COALESCE(block_style_presets, '{}'::jsonb), CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
              )
            """,
        ).bind("t", tenantKey).bind("jsonPath", FONT_REFERENCE_JSON_PATH).bind("vars", vars)
            .map { rs, _ ->
                fontConflictIfExternal(
                    type = "theme",
                    name = rs.getString("name"),
                    referencingCatalog = rs.getString("catalog_key"),
                    owningCatalog = catalogKey,
                    fontSlug = slug,
                )
            }
            .list()
            .filterNotNull()
            .let { conflicts.addAll(it) }

        handle.createQuery(
            """
            SELECT DISTINCT dt.name, dt.catalog_key
            FROM template_versions ver
            JOIN document_templates dt
              ON dt.tenant_key = ver.tenant_key
             AND dt.catalog_key = ver.catalog_key
             AND dt.id = ver.template_key
            WHERE ver.tenant_key = :t
              AND ver.status IN ('draft', 'published')
              AND jsonb_path_exists(ver.template_model, CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
            """,
        ).bind("t", tenantKey).bind("jsonPath", FONT_REFERENCE_JSON_PATH).bind("vars", vars)
            .map { rs, _ ->
                fontConflictIfExternal(
                    type = "template",
                    name = rs.getString("name"),
                    referencingCatalog = rs.getString("catalog_key"),
                    owningCatalog = catalogKey,
                    fontSlug = slug,
                )
            }
            .list()
            .filterNotNull()
            .let { conflicts.addAll(it) }
    }

    private fun fontConflictIfExternal(
        type: String,
        name: String,
        referencingCatalog: String,
        owningCatalog: CatalogKey,
        fontSlug: String,
    ): String? {
        if (referencingCatalog == owningCatalog.value) return null
        return "Font '$fontSlug' is used by $type '$name' (catalog: $referencingCatalog)"
    }

    /**
     * Hard-deletes the given stale resources from their owning tables, in
     * dependency order (templates first — they may reference themes/stencils —
     * then the rest). Idempotent; returns what was removed. Callers must have
     * already cleared [findConflicts]. Shared by `UpgradeCatalog` and the
     * SUBSCRIBED ZIP-import path so "what stale-prune deletes" has one
     * definition.
     */
    fun removeStale(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        staleResources: List<InstalledResource>,
    ): List<RemovedResource> {
        if (staleResources.isEmpty()) return emptyList()

        data class ResourceTable(val table: String, val keyColumn: String)

        val tableByType = mapOf(
            "template" to ResourceTable("document_templates", "id"),
            "stencil" to ResourceTable("stencils", "id"),
            "attribute" to ResourceTable("variant_attribute_definitions", "id"),
            "theme" to ResourceTable("themes", "id"),
            "asset" to ResourceTable("assets", "id"),
            "codeList" to ResourceTable("code_lists", "slug"),
            "font" to ResourceTable("fonts", "slug"),
        )
        val ordered = staleResources.sortedByDescending { RESOURCE_INSTALL_ORDER[it.type] ?: Int.MIN_VALUE }

        val removed = mutableListOf<RemovedResource>()
        jdbi.useHandle<Exception> { handle ->
            for (resource in ordered) {
                val target = tableByType[resource.type] ?: continue
                handle.createUpdate("DELETE FROM ${target.table} WHERE tenant_key = :t AND catalog_key = :c AND ${target.keyColumn} = :id")
                    .bind("t", tenantKey).bind("c", catalogKey).bind("id", resource.slug).execute()
                removed.add(RemovedResource(resource.type, resource.slug))
                logger.info("Removed stale {} '{}' from catalog '{}'", resource.type, resource.slug, catalogKey)
            }
        }
        return removed
    }
}
