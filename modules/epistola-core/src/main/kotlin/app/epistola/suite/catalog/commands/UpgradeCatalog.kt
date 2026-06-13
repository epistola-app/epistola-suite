package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.RemovedResource
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
import tools.jackson.databind.ObjectMapper

/**
 * Upgrades a subscribed catalog by re-fetching from its source URL.
 *
 * The upgrade is validated before execution: if removing stale resources
 * would break cross-catalog references (themes used by other templates,
 * stencils embedded in other template models, etc.), the entire upgrade
 * is rejected with a [CatalogUpgradeConflictException].
 *
 * Only resources that are already installed locally are upgraded — new
 * resources in the manifest are not automatically installed, **unless** their
 * slug is listed in [includeNewSlugs] (the opt-in "also install new resources"
 * path, wired from the upgrade preview's ADDED bucket — same slug semantics as
 * `InstallFromCatalog.resourceSlugs`). Empty = the default by-design
 * behaviour (upgrade-in-place only).
 */
data class UpgradeCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val includeNewSlugs: List<String> = emptyList(),
) : Command<UpgradeCatalogResult>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

data class UpgradeCatalogResult(
    val previousVersion: String?,
    val newVersion: String,
    val installResults: List<InstallResult>,
    val removedResources: List<RemovedResource>,
    /**
     * True when one or more resource installs FAILED: stale resources were NOT
     * removed and the installed version/fingerprint were NOT advanced, so the
     * catalog stays on [previousVersion] and the next run retries. Never a
     * silent partial upgrade.
     */
    val aborted: Boolean = false,
)

/** Thrown by the system reconciler when an upgrade aborted on a failed install. */
class CatalogUpgradeAbortedException(
    val catalogKey: CatalogKey,
    val failed: List<InstallResult>,
) : RuntimeException(
    "Catalog '${catalogKey.value}' upgrade aborted — ${failed.size} resource(s) failed to install:\n" +
        failed.joinToString("\n") { "  - ${it.type}/${it.slug}: ${it.errorMessage}" },
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
    private val analyzer: CatalogUpgradeAnalyzer,
    private val fingerprintService: CatalogFingerprintService,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpgradeCatalog, UpgradeCatalogResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: UpgradeCatalog): UpgradeCatalogResult = CatalogImportContext.runAsImport {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog '${command.catalogKey}' has no source URL — only subscribed catalogs can be upgraded")

        val previousVersion = catalog.installedReleaseVersion

        // 1. Fetch manifest (once — reused for install and stale check)
        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
        val manifestSlugs = manifest.resources.groupBy({ it.type }, { it.slug })

        // 2. Find which resources are currently installed locally
        val installedSlugs = analyzer.installedByType(command.tenantKey, command.catalogKey)

        // 3. Compute what would be removed
        val staleResources = analyzer.computeStale(installedSlugs, manifestSlugs)

        // 4. Validate: fail if any stale resource is still referenced
        if (staleResources.isNotEmpty()) {
            val conflicts = analyzer.findConflicts(command.tenantKey, command.catalogKey, staleResources)
            if (conflicts.isNotEmpty()) {
                throw CatalogUpgradeConflictException(conflicts)
            }
        }

        // 5. Install/update previously installed resources, plus any opted-in
        //    new resources (filtered to slugs the new manifest actually ships).
        val manifestSlugSet = manifest.resources.map { it.slug }.toSet()
        val newSlugs = command.includeNewSlugs.filter { it in manifestSlugSet }
        val slugsToUpgrade = (installedSlugs.values.flatten().map { it.slug } + newSlugs).distinct()
        val installResults = if (slugsToUpgrade.isNotEmpty()) {
            InstallFromCatalog(
                tenantKey = command.tenantKey,
                catalogKey = command.catalogKey,
                resourceSlugs = slugsToUpgrade,
            ).execute()
        } else {
            emptyList()
        }

        // 5b. Abort before any destructive change if an install FAILED. The
        //     per-resource try/catch swallows failures into InstallResult, so
        //     without this an upgrade would still remove stale resources and
        //     bump the version — a silent, permanent half-upgrade. Leave
        //     version/fingerprint untouched so the next run retries.
        val failedInstalls = installResults.filter { it.status == InstallStatus.FAILED }
        if (failedInstalls.isNotEmpty()) {
            logger.error(
                "Catalog '{}' upgrade ABORTED for tenant {} — {} resource(s) failed; not removing stale resources, not bumping version (stays {}): {}",
                command.catalogKey,
                command.tenantKey.value,
                failedInstalls.size,
                previousVersion,
                failedInstalls.joinToString { "${it.type}/${it.slug}: ${it.errorMessage}" },
            )
            return@runAsImport UpgradeCatalogResult(
                previousVersion = previousVersion,
                newVersion = manifest.release.version,
                installResults = installResults,
                removedResources = emptyList(),
                aborted = true,
            )
        }

        // 6. Remove stale resources (already validated) — shared definition.
        val removed = analyzer.removeStale(command.tenantKey, command.catalogKey, staleResources)

        // 7. Bump version last — only reached when all installs succeeded.
        //    Re-capture the source-side per-resource baseline of the release we
        //    just moved to (same provenance as installed_fingerprint), so the
        //    next preview diffs against this release, not the old one.
        val resourceFingerprintsJson = objectMapper.writeValueAsString(
            fingerprintService.perResourceFingerprintsFromSource(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value),
        )
        updateCatalogVersion(
            command.tenantKey,
            command.catalogKey,
            manifest.release.version,
            manifest.release.fingerprint,
            resourceFingerprintsJson,
            manifest.catalog.name,
            manifest.catalog.description,
        )

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

    private fun updateCatalogVersion(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        version: String,
        fingerprint: String?,
        resourceFingerprintsJson: String,
        name: String,
        description: String?,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET installed_release_version = :version, installed_fingerprint = :fingerprint,
                    installed_resource_fingerprints = :resourceFingerprints::jsonb,
                    name = :name, description = :description, updated_at = NOW()
                WHERE tenant_key = :t AND id = :c
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("fingerprint", fingerprint)
                .bind("resourceFingerprints", resourceFingerprintsJson)
                .bind("name", name)
                .bind("description", description)
                .execute()
        }
    }
}
