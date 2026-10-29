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
    private val graphs: app.epistola.suite.catalog.graph.TenantResourceGraphBuilder,
) {

    private companion object {
        val STALE_PRUNED_RESOURCE_TYPES = RESOURCE_INSTALL_ORDER.keys

        /** How a conflict names a resource's type. */
        val TYPE_WORDS = mapOf("codeList" to "Code list")
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
     * Human-readable cross-catalog conflicts for the given stale resources (a removed resource still
     * used from another catalog), from the resource graph, the one reference authority: any kind of
     * use counts (a template's theme, stencils, images and fonts, a theme's fonts, a variant's
     * attributes, an attribute's code list, a stencil's stencils), and so does a theme that is the
     * tenant's default. What the catalog uses of itself goes with it. Empty = safe.
     */
    fun findConflicts(
        catalog: CatalogId,
        staleResources: List<InstalledResource>,
    ): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        if (staleResources.isEmpty()) return@withHandle emptyList()
        val graph = graphs.buildOn(handle, catalog.tenantKey)
        val names = graph.nodes.associate { it.address to it.name }
        val stale = staleResources.associateBy { it.type to it.slug }
        val conflicts = graph.edges.mapNotNull { edge ->
            val target = edge.target ?: return@mapNotNull null
            val resource = stale[target.type.wireName to target.key] ?: return@mapNotNull null
            if (target.catalogAddress != catalog.address || edge.source.catalogAddress == catalog.address) return@mapNotNull null
            val word = TYPE_WORDS[resource.type] ?: resource.type.replaceFirstChar(Char::uppercase)
            "$word '${resource.slug}' is used by ${edge.source.type.wireName} '${names[edge.source] ?: edge.source.key}' (catalog: ${edge.source.catalogKey})"
        }
        val defaultTheme = staleResources.filter { it.type == "theme" }.mapNotNull { theme ->
            handle.createQuery(
                """
                SELECT t.id FROM tenants t
                JOIN current_themes th ON th.tenant_key = t.id AND th.resource_id = t.default_theme_resource_id
                WHERE t.id = :t AND th.catalog_id = ${catalogAt("t", "c")} AND th.id = :slug
                """,
            ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("slug", theme.slug)
                .mapTo(String::class.java).findOne().map { "Theme '${theme.slug}' is the tenant default theme" }.orElse(null)
        }
        (conflicts.distinct().sorted() + defaultTheme)
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
