// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
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
 * source), the import path that receives a release
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

        // A `fontFamily` reference names its font by `key` from wire v8; content stored before 2.0
        // says `slug` until the upgrade rewrites it, and still uses the font.
        const val FONT_REFERENCE_JSON_PATH = "\$.**.fontFamily ? ((@.key == \$slug || @.slug == \$slug) && @.catalogKey == \$catalogKey)"
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Every resource installed under `(tenant, catalog)`, including resource types not subject to stale pruning. */
    fun installedResources(catalog: CatalogId): Set<InstalledResource> = jdbi.withHandle<Set<InstalledResource>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT 'template' AS type, id::text AS slug FROM current_templates WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            UNION ALL SELECT 'theme', id::text FROM current_themes WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            UNION ALL SELECT 'stencil', id::text FROM current_stencils WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")}
            UNION ALL SELECT 'attribute', id FROM current_attributes WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            UNION ALL SELECT 'image', id::text FROM current_images WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")}
            UNION ALL SELECT 'codeList', id FROM current_code_lists WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            UNION ALL SELECT 'font', slug::text FROM current_fonts WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")}
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .map { rs, _ -> InstalledResource(rs.getString("type"), rs.getString("slug")) }
            .set()
    }

    /** Resources handled by the upgrade analyzer, grouped by type. */
    fun installedByType(catalog: CatalogId): Map<String, List<InstalledResource>> = installedResources(catalog)
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
        catalog: CatalogId,
        staleResources: List<InstalledResource>,
    ): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        val conflicts = mutableListOf<String>()
        for (resource in staleResources) {
            when (resource.type) {
                "theme" -> findThemeConflicts(handle, catalog, resource.slug, conflicts)

                "stencil" -> findStencilConflicts(handle, catalog, resource.slug, conflicts)

                // A template removed by an upgrade breaks nothing that renders: environments serve
                // releases, which keep their content, not the working copy's templates.
                "attribute" -> findAttributeConflicts(handle, catalog, resource.slug, conflicts)

                "codeList" -> findCodeListConflicts(handle, catalog, resource.slug, conflicts)

                "font" -> findFontConflicts(handle, catalog, resource.slug, conflicts)
            }
        }
        conflicts
    }

    private fun findThemeConflicts(handle: Handle, catalog: CatalogId, slug: String, conflicts: MutableList<String>) {
        handle.createQuery(
            """
            SELECT dt.name, dc.catalog_key AS catalog_key FROM current_templates dt
            JOIN catalogs dc ON dc.tenant_key = dt.tenant_key AND dc.catalog_id = dt.catalog_id
            JOIN current_themes th ON th.tenant_key = dt.tenant_key AND th.resource_id = dt.theme_resource_id
            WHERE dt.tenant_key = :t AND th.catalog_id = ${catalogAt("t", "c")} AND th.id = :slug AND dt.catalog_id != ${catalogAt("t", "c")}
            """,
        ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("slug", slug)
            .map { rs, _ -> "Theme '$slug' is used by template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")})" }
            .list().let { conflicts.addAll(it) }

        handle.createQuery(
            """
            SELECT t.id FROM tenants t
            JOIN current_themes th ON th.tenant_key = t.id AND th.resource_id = t.default_theme_resource_id
            WHERE t.id = :t AND th.catalog_id = ${catalogAt("t", "c")} AND th.id = :slug
            """,
        ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("slug", slug)
            .mapTo(String::class.java).findOne().ifPresent {
                conflicts.add("Theme '$slug' is the tenant default theme")
            }
    }

    private fun findStencilConflicts(handle: Handle, catalog: CatalogId, slug: String, conflicts: MutableList<String>) {
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, dc.catalog_key AS catalog_key
            FROM current_template_variants tv
            JOIN current_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_id = tv.catalog_id AND dt.resource_id = tv.template_resource_id
            JOIN catalogs dc ON dc.tenant_key = dt.tenant_key AND dc.catalog_id = dt.catalog_id
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            WHERE tv.tenant_key = :t AND dt.catalog_id != ${catalogAt("t", "c")}
              AND n.value ->> 'type' = 'stencil'
              AND n.value -> 'props' ->> 'catalog.key' = :cStr
              AND n.value -> 'props' ->> 'stencilKey' = :slug
            """,
        ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("cStr", catalog.key.value).bind("slug", slug)
            .map { rs, _ -> "Stencil '$slug' is used by template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")})" }
            .list().let { conflicts.addAll(it) }
    }

    private fun findAttributeConflicts(handle: Handle, catalog: CatalogId, slug: String, conflicts: MutableList<String>) {
        handle.createQuery(
            """
            -- What another catalog's current version uses it for, from the reference index (ADR 0039):
            -- a variant names it as `catalog.attribute` (that catalog's alias) or by its key alone.
            SELECT DISTINCT r.resource_type, r.name, dc.catalog_key AS catalog_key
            FROM release_wire_resources r
            JOIN catalog_current_versions cv ON cv.tenant_key = r.tenant_key AND cv.version_id = r.version_id
            JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash
            JOIN catalogs dc ON dc.tenant_key = r.tenant_key AND dc.catalog_id = r.catalog_id
            WHERE r.tenant_key = :t AND r.catalog_id != ${catalogAt("t", "c")}
              AND x.resource_type = 'attribute' AND x.resource_key = :slug
              AND (x.alias = :cStr OR x.alias IS NULL)
            """,
        ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("cStr", catalog.key.value).bind("slug", slug)
            .map { rs, _ -> "Attribute '$slug' is used by ${rs.getString("resource_type")} '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")})" }
            .list().let { conflicts.addAll(it) }
    }

    private fun findCodeListConflicts(
        handle: Handle,
        catalog: CatalogId,
        slug: String,
        conflicts: MutableList<String>,
    ) {
        handle.createQuery(
            """
            -- Resolved through the one alias resolver (catalog_version_references, ADR 0033).
            SELECT vad.name AS display_name, vc.catalog_key AS catalog_key
            FROM catalog_version_references('attribute') x
            JOIN release_wire_resources vad ON vad.tenant_key = x.tenant_key AND vad.version_id = x.version_id AND vad.resource_type = 'attribute' AND vad.resource_key = x.resource_key
            JOIN catalogs vc ON vc.tenant_key = x.tenant_key AND vc.catalog_id = x.catalog_id
            WHERE x.tenant_key = :t
              AND x.catalog_id != ${catalogAt("t", "c")}
              AND x.ref_type = 'codeList' AND x.ref_catalog_id = ${catalogAt("t", "c")} AND x.ref_key = CAST(:slug AS TEXT)
            """,
        ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("slug", slug)
            .map { rs, _ ->
                "Code list '$slug' is used by attribute '${rs.getString("display_name")}' (catalog: ${rs.getString("catalog_key")})"
            }
            .list()
            .let { conflicts.addAll(it) }
    }

    private fun findFontConflicts(
        handle: Handle,
        catalog: CatalogId,
        slug: String,
        conflicts: MutableList<String>,
    ) {
        val vars = """{"slug":"$slug","catalogKey":"${catalog.key.value}"}"""

        handle.createQuery(
            """
            SELECT th.name, tc.catalog_key AS catalog_key
            FROM current_themes th
            JOIN catalogs tc ON tc.tenant_key = th.tenant_key AND tc.catalog_id = th.catalog_id
            WHERE th.tenant_key = :t
             
              AND (
                jsonb_path_exists(th.document_styles, CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
                OR jsonb_path_exists(COALESCE(th.block_style_presets, '{}'::jsonb), CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
              )
            """,
        ).bind("t", catalog.tenantKey).bind("jsonPath", FONT_REFERENCE_JSON_PATH).bind("vars", vars)
            .map { rs, _ ->
                fontConflictIfExternal(
                    type = "theme",
                    name = rs.getString("name"),
                    referencingCatalog = rs.getString("catalog_key"),
                    owningCatalog = catalog.key,
                    fontSlug = slug,
                )
            }
            .list()
            .filterNotNull()
            .let { conflicts.addAll(it) }

        handle.createQuery(
            """
            SELECT DISTINCT dt.name, dc.catalog_key AS catalog_key
            FROM current_template_variants ver
            JOIN current_templates dt
              ON dt.tenant_key = ver.tenant_key
             AND dt.catalog_id = ver.catalog_id
             AND dt.resource_id = ver.template_resource_id
            JOIN catalogs dc ON dc.tenant_key = dt.tenant_key AND dc.catalog_id = dt.catalog_id
            WHERE ver.tenant_key = :t
              AND jsonb_path_exists(ver.template_model, CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
            """,
        ).bind("t", catalog.tenantKey).bind("jsonPath", FONT_REFERENCE_JSON_PATH).bind("vars", vars)
            .map { rs, _ ->
                fontConflictIfExternal(
                    type = "template",
                    name = rs.getString("name"),
                    referencingCatalog = rs.getString("catalog_key"),
                    owningCatalog = catalog.key,
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
     * import path that receives a release so "what stale-prune deletes" has one
     * definition.
     */
    fun removeStale(
        catalog: CatalogId,
        staleResources: List<InstalledResource>,
    ): List<RemovedResource> {
        if (staleResources.isEmpty()) return emptyList()

        val ordered = staleResources.sortedByDescending { RESOURCE_INSTALL_ORDER[it.type] ?: Int.MIN_VALUE }

        val removed = mutableListOf<RemovedResource>()
        jdbi.useHandle<Exception> { handle ->
            for (resource in ordered) {
                if (resource.type in app.epistola.suite.catalog.revisions.StoredContent.ENVELOPED_TYPES) {
                    // Every type is a draft row naming its content (ADR 0033).
                    app.epistola.suite.catalog.revisions.DraftResources.delete(handle, catalog, resource.type, resource.slug)
                    removed.add(RemovedResource(resource.type, resource.slug))
                    logger.info("Removed stale {} '{}' from catalog '{}'", resource.type, resource.slug, catalog.key)
                    continue
                }
            }
        }
        return removed
    }
}
