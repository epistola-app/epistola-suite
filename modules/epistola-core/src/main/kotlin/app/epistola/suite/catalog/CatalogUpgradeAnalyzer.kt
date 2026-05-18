package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A resource currently installed under a catalog. */
data class InstalledResource(val type: String, val slug: String)

/**
 * Shared upgrade analysis — installed-resource discovery, stale computation and
 * cross-catalog conflict detection — used by both
 * [UpgradeCatalog][app.epistola.suite.catalog.commands.UpgradeCatalog] (at
 * apply time, where conflicts throw) and
 * [PreviewCatalogUpgrade][app.epistola.suite.catalog.queries.PreviewCatalogUpgrade]
 * (so the same conflicts surface up front, not only at apply).
 */
@Component
class CatalogUpgradeAnalyzer(
    private val jdbi: Jdbi,
) {

    /** All resources installed under `(tenant, catalog)`, grouped by type. */
    fun installedByType(tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, List<InstalledResource>> = jdbi.withHandle<Map<String, List<InstalledResource>>, Exception> { handle ->
        val resources = mutableListOf<InstalledResource>()

        data class TableType(val type: String, val table: String)

        val tables = listOf(
            TableType("template", "document_templates"),
            TableType("theme", "themes"),
            TableType("stencil", "stencils"),
            TableType("attribute", "variant_attribute_definitions"),
            TableType("asset", "assets"),
        )

        for ((type, table) in tables) {
            handle.createQuery("SELECT id FROM $table WHERE tenant_key = :t AND catalog_key = :c")
                .bind("t", tenantKey).bind("c", catalogKey).mapTo(String::class.java).list()
                .forEach { resources.add(InstalledResource(type, it)) }
        }

        resources.groupBy { it.type }
    }

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
              AND v.attributes::jsonb ? :slug
            """,
        ).bind("t", tenantKey).bind("c", catalogKey).bind("slug", slug)
            .map { rs, _ -> "Attribute '$slug' is used by template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")})" }
            .list().let { conflicts.addAll(it) }
    }
}
