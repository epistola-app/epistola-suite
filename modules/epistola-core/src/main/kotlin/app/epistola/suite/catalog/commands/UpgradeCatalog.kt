package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Upgrades a subscribed catalog by re-fetching from its source URL.
 *
 * The upgrade is validated before execution: if removing stale resources
 * would break cross-catalog references (themes used by other templates,
 * stencils embedded in other template models, etc.), the entire upgrade
 * is rejected with a [CatalogUpgradeConflictException].
 *
 * Only resources that are already installed locally are upgraded — new
 * resources in the manifest are not automatically installed.
 */
data class UpgradeCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<UpgradeCatalogResult>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

data class UpgradeCatalogResult(
    val previousVersion: String?,
    val newVersion: String,
    val installResults: List<InstallResult>,
    val removedResources: List<RemovedResource>,
)

data class RemovedResource(
    val type: String,
    val slug: String,
)

class CatalogUpgradeConflictException(
    val conflicts: List<String>,
) : RuntimeException(
    "Catalog upgrade blocked — the following resources would be removed but are still in use:\n" +
        conflicts.joinToString("\n") { "  - $it" },
)

@Component
class UpgradeCatalogHandler(
    private val jdbi: Jdbi,
    private val catalogClient: CatalogClient,
) : CommandHandler<UpgradeCatalog, UpgradeCatalogResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: UpgradeCatalog): UpgradeCatalogResult = CatalogImportContext.runAsImport {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog '${command.catalogKey}' has no source URL — only subscribed catalogs can be upgraded")

        val previousVersion = catalog.installedReleaseVersion

        // 1. Fetch manifest (once — reused for install and stale check)
        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)
        val manifestSlugs = manifest.resources.groupBy({ it.type }, { it.slug })

        // 2. Find which resources are currently installed locally
        val installedSlugs = findInstalledResourceSlugs(command.tenantKey, command.catalogKey)

        // 3. Compute what would be removed
        val staleResources = computeStaleResources(installedSlugs, manifestSlugs)

        // 4. Validate: fail if any stale resource is still referenced
        if (staleResources.isNotEmpty()) {
            val conflicts = findConflicts(command.tenantKey, command.catalogKey, staleResources)
            if (conflicts.isNotEmpty()) {
                throw CatalogUpgradeConflictException(conflicts)
            }
        }

        // 5. Install/update only previously installed resources
        val slugsToUpgrade = installedSlugs.values.flatten().map { it.slug }
        val installResults = if (slugsToUpgrade.isNotEmpty()) {
            InstallFromCatalog(
                tenantKey = command.tenantKey,
                catalogKey = command.catalogKey,
                resourceSlugs = slugsToUpgrade,
            ).execute()
        } else {
            emptyList()
        }

        // 6. Remove stale resources (already validated)
        val removed = removeStaleResources(command.tenantKey, command.catalogKey, staleResources)

        // 7. Bump version last — if any step above fails, version stays at the old value
        //    and the next run will retry the upgrade.
        updateCatalogVersion(command.tenantKey, command.catalogKey, manifest.release.version, manifest.catalog.name, manifest.catalog.description)

        val newVersion = manifest.release.version
        val installed = installResults.count { it.status == InstallStatus.INSTALLED }
        val updatedCount = installResults.count { it.status == InstallStatus.UPDATED }
        val failed = installResults.count { it.status == InstallStatus.FAILED }
        logger.info(
            "Upgraded catalog '{}': {} -> {}, {} installed, {} updated, {} failed, {} removed",
            command.catalogKey,
            previousVersion,
            newVersion,
            installed,
            updatedCount,
            failed,
            removed.size,
        )

        UpgradeCatalogResult(
            previousVersion = previousVersion,
            newVersion = newVersion,
            installResults = installResults,
            removedResources = removed,
        )
    }

    // ── Installed resource discovery ──────────────────────────────────────────

    private data class InstalledResource(val type: String, val slug: String)

    private fun findInstalledResourceSlugs(tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, List<InstalledResource>> = jdbi.withHandle<Map<String, List<InstalledResource>>, Exception> { handle ->
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

    // ── Stale resource computation ───────────────────────────────────────────

    private fun computeStaleResources(
        installedSlugs: Map<String, List<InstalledResource>>,
        manifestSlugs: Map<String, List<String>>,
    ): List<InstalledResource> {
        val stale = mutableListOf<InstalledResource>()
        for ((type, installed) in installedSlugs) {
            val inManifest = manifestSlugs[type]?.toSet() ?: emptySet()
            installed.filter { it.slug !in inManifest }.forEach { stale.add(it) }
        }
        return stale
    }

    // ── Conflict validation ──────────────────────────────────────────────────

    /**
     * Checks all stale resources for cross-catalog references.
     * Returns human-readable conflict descriptions. Empty list = safe to proceed.
     */
    private fun findConflicts(
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
        // Templates in other catalogs referencing this theme
        handle.createQuery(
            """
            SELECT name, catalog_key FROM document_templates
            WHERE tenant_key = :t AND theme_catalog_key = :c AND theme_key = :slug AND catalog_key != :c
            """,
        ).bind("t", tenantKey).bind("c", catalogKey).bind("slug", slug)
            .map { rs, _ -> "Theme '$slug' is used by template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")})" }
            .list().let { conflicts.addAll(it) }

        // Tenant default theme
        handle.createQuery(
            "SELECT id FROM tenants WHERE id = :t AND default_theme_catalog_key = :c AND default_theme_key = :slug",
        ).bind("t", tenantKey).bind("c", catalogKey).bind("slug", slug)
            .mapTo(String::class.java).findOne().ifPresent {
                conflicts.add("Theme '$slug' is the tenant default theme")
            }
    }

    private fun findStencilConflicts(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, slug: String, conflicts: MutableList<String>) {
        // Templates in other catalogs that embed this stencil in their template model
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
        // Template has active environment activations
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
        // Variants in other catalogs that use this attribute
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

    // ── Stale resource removal ───────────────────────────────────────────────

    private fun removeStaleResources(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        staleResources: List<InstalledResource>,
    ): List<RemovedResource> {
        if (staleResources.isEmpty()) return emptyList()

        val removed = mutableListOf<RemovedResource>()

        val tableByType = mapOf(
            "template" to "document_templates",
            "stencil" to "stencils",
            "attribute" to "variant_attribute_definitions",
            "theme" to "themes",
            "asset" to "assets",
        )

        // Delete in dependency order: templates first (may reference themes/stencils), then the rest
        val ordered = staleResources.sortedBy {
            when (it.type) {
                "template" -> 0
                "stencil" -> 1
                "attribute" -> 2
                "asset" -> 3
                "theme" -> 4
                else -> 5
            }
        }

        jdbi.useHandle<Exception> { handle ->
            for (resource in ordered) {
                val table = tableByType[resource.type] ?: continue
                handle.createUpdate("DELETE FROM $table WHERE tenant_key = :t AND catalog_key = :c AND id = :id")
                    .bind("t", tenantKey).bind("c", catalogKey).bind("id", resource.slug).execute()
                removed.add(RemovedResource(resource.type, resource.slug))
                logger.info("Removed stale {} '{}' from catalog '{}'", resource.type, resource.slug, catalogKey)
            }
        }

        return removed
    }

    private fun updateCatalogVersion(tenantKey: TenantKey, catalogKey: CatalogKey, version: String, name: String, description: String?) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET installed_release_version = :version, name = :name, description = :description, last_modified = NOW()
                WHERE tenant_key = :t AND id = :c
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("name", name)
                .bind("description", description)
                .execute()
        }
    }
}
