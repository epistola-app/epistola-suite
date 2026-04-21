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
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Upgrades a subscribed catalog by re-fetching from its source URL.
 * Updates catalog metadata and version, upgrades previously installed
 * resources, and removes installed resources no longer in the manifest.
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

        // 1. Re-register: upserts metadata + version
        val updated = RegisterCatalog(
            tenantKey = command.tenantKey,
            sourceUrl = sourceUrl,
            authType = catalog.sourceAuthType,
            authCredential = catalog.sourceAuthCredential,
        ).execute()

        // 2. Find which resources are currently installed locally
        val installedSlugs = findInstalledResourceSlugs(command.tenantKey, command.catalogKey)

        // 3. Upgrade only previously installed resources
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

        // 4. Remove installed resources no longer in the manifest
        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)
        val manifestSlugs = manifest.resources.groupBy({ it.type }, { it.slug })
        val removed = removeStaleResources(command.tenantKey, command.catalogKey, installedSlugs, manifestSlugs)

        val installed = installResults.count { it.status == InstallStatus.INSTALLED }
        val updatedCount = installResults.count { it.status == InstallStatus.UPDATED }
        val failed = installResults.count { it.status == InstallStatus.FAILED }
        logger.info(
            "Upgraded catalog '{}': {} -> {}, {} installed, {} updated, {} failed, {} removed",
            command.catalogKey,
            previousVersion,
            updated.installedReleaseVersion,
            installed,
            updatedCount,
            failed,
            removed.size,
        )

        UpgradeCatalogResult(
            previousVersion = previousVersion,
            newVersion = updated.installedReleaseVersion ?: manifest.release.version,
            installResults = installResults,
            removedResources = removed,
        )
    }

    private data class InstalledResource(val type: String, val slug: String)

    private fun findInstalledResourceSlugs(tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, List<InstalledResource>> = jdbi.withHandle<Map<String, List<InstalledResource>>, Exception> { handle ->
        val resources = mutableListOf<InstalledResource>()

        handle.createQuery("SELECT id FROM document_templates WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenantKey).bind("c", catalogKey).mapTo(String::class.java).list()
            .forEach { resources.add(InstalledResource("template", it)) }

        handle.createQuery("SELECT id FROM themes WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenantKey).bind("c", catalogKey).mapTo(String::class.java).list()
            .forEach { resources.add(InstalledResource("theme", it)) }

        handle.createQuery("SELECT id FROM stencils WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenantKey).bind("c", catalogKey).mapTo(String::class.java).list()
            .forEach { resources.add(InstalledResource("stencil", it)) }

        handle.createQuery("SELECT id FROM variant_attribute_definitions WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenantKey).bind("c", catalogKey).mapTo(String::class.java).list()
            .forEach { resources.add(InstalledResource("attribute", it)) }

        resources.groupBy { it.type }
    }

    /**
     * Removes locally installed resources that are no longer in the remote manifest.
     * Only deletes resources that were previously installed, not all resources in the catalog.
     */
    private fun removeStaleResources(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        installedSlugs: Map<String, List<InstalledResource>>,
        manifestSlugs: Map<String, List<String>>,
    ): List<RemovedResource> {
        val removed = mutableListOf<RemovedResource>()

        data class TableMapping(val type: String, val table: String)

        val tables = listOf(
            TableMapping("template", "document_templates"),
            TableMapping("theme", "themes"),
            TableMapping("stencil", "stencils"),
            TableMapping("attribute", "variant_attribute_definitions"),
        )

        jdbi.useHandle<Exception> { handle ->
            for ((type, table) in tables) {
                val installed = installedSlugs[type]?.map { it.slug } ?: continue
                val inManifest = manifestSlugs[type]?.toSet() ?: emptySet()
                val toRemove = installed.filter { it !in inManifest }

                for (slug in toRemove) {
                    handle.createUpdate("DELETE FROM $table WHERE tenant_key = :t AND catalog_key = :c AND id = :id")
                        .bind("t", tenantKey).bind("c", catalogKey).bind("id", slug).execute()
                    removed.add(RemovedResource(type, slug))
                    logger.info("Removed stale {} '{}' from catalog '{}'", type, slug, catalogKey)
                }
            }
        }

        return removed
    }
}
