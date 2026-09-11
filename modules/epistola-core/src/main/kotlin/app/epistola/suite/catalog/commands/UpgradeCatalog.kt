// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogMetadata
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogNotUpgradeableException
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.RemovedResource
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
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
 * An upgrade reconciles the **whole** manifest: resources that are new since the installed release
 * are added, changed ones are updated, and ones the publisher no longer publishes are removed,
 * subject to the conflict check below. There is no selective mode. An upgrade that preserved a
 * locally chosen subset made "installed 1.2.0" mean something different on every installation; a
 * catalog is one install unit (see issue #850 and [InstallFromCatalog]).
 *
 * [preserveResourceTypes] is not an exception to that: it names resources deliberately managed
 * outside the manifest, such as the system fonts seeded by `EnsureSystemFonts`, so reconciliation
 * does not delete what the manifest was never responsible for.
 */
data class UpgradeCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val preserveResourceTypes: Set<String> = emptySet(),
) : Command<UpgradeCatalogResult>,
    RequiresPermission,
    // Re-fetches the remote catalog over HTTP mid-command.
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_MANAGE
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
            ?: throw CatalogNotFoundException(command.catalogKey)

        val sourceUrl = catalog.sourceUrl
            ?: throw CatalogNotUpgradeableException(
                command.catalogKey,
                "only subscribed catalogs with a source URL can be upgraded",
            )

        val previousVersion = catalog.installedReleaseVersion

        // 1. Fetch manifest (once — reused for install and stale check)
        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
        val manifestSlugs = manifest.resources.groupBy({ it.type }, { it.slug })

        // 2. Find which resources are currently installed locally
        val installedSlugs = analyzer.installedByType(command.tenantKey, command.catalogKey)

        // 3. Compute what would be removed
        val staleResources = analyzer.computeStale(installedSlugs, manifestSlugs)
            .filterNot { it.type in command.preserveResourceTypes }

        // 4. Validate: fail if any stale resource is still referenced
        if (staleResources.isNotEmpty()) {
            val conflicts = analyzer.findConflicts(command.tenantKey, command.catalogKey, staleResources)
            if (conflicts.isNotEmpty()) {
                throw CatalogUpgradeConflictException(conflicts)
            }
        }

        // 5. Reconcile the whole manifest. Resources added since the installed release arrive with
        //    this upgrade rather than waiting to be asked for individually.
        val installResults = if (manifest.resources.isNotEmpty()) {
            InstallFromCatalog(
                tenantKey = command.tenantKey,
                catalogKey = command.catalogKey,
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
            objectMapper.writeValueAsString(CatalogMetadata.from(manifest.catalog)),
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
        catalogMetadataJson: String,
    ) {
        // #692: bound the re-fetched manifest name to catalogs.name VARCHAR(255).
        validateCatalogNameLength(name)
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET installed_release_version = :version, installed_fingerprint = :fingerprint,
                    installed_resource_fingerprints = :resourceFingerprints::jsonb,
                    name = :name, description = :description, catalog_metadata = :catalogMetadata::jsonb,
                    installed_at = NOW(), content_updated_at = NOW(), updated_at = NOW()
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
                .bind("catalogMetadata", catalogMetadataJson)
                .execute()
        }
    }
}
