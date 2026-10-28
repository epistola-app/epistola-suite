// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogNotUpgradeableException
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.RemovedResource
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.sources.ManifestUrlReleaseSource
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Upgrades a subscribed catalog to the release its source URL offers now.
 *
 * Installing is importing (contract ADR 0008): the release goes through [ImportCatalogZip], as one
 * received any other way. Without changes of its own the next version moves to it: what is new is
 * added, what changed is updated, and what the publisher no longer publishes is removed, unless it is
 * still used from another catalog ([CatalogUpgradeConflictException], before anything changes). With
 * changes of its own, the release is recorded beside the next version, to be merged (ADR 0032).
 * There is no selective mode: a catalog is one install unit (issue #850).
 */
data class UpgradeCatalog(
    val catalog: CatalogId,
    /** How the release arrives, when not as its source says (the upgrade from 1.x): shown, never decided by. */
    val provenance: app.epistola.suite.catalog.releases.ReleaseProvenance? = null,
) : Command<UpgradeCatalogResult>,
    RequiresPermission,
    // Re-fetches the remote catalog over HTTP mid-command.
    SelfManagedTransaction {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
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
    private val analyzer: CatalogUpgradeAnalyzer,
    private val source: ManifestUrlReleaseSource,
) : CommandHandler<UpgradeCatalog, UpgradeCatalogResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: UpgradeCatalog): UpgradeCatalogResult = CatalogImportContext.runAsImport {
        val catalog = GetCatalog(command.catalog).query()
            ?: throw CatalogNotFoundException(command.catalogKey)
        if (catalog.sourceUrl == null) {
            throw CatalogNotUpgradeableException(command.catalogKey, "only a catalog with a source URL can be upgraded")
        }
        val previousVersion = catalog.latestReleaseVersion
        val before = analyzer.installedResources(command.catalog)
        val imported = installFromSource(source, command.catalog, command.provenance)
        val removed = if (imported.aborted) {
            emptyList()
        } else {
            (before - analyzer.installedResources(command.catalog)).map { RemovedResource(it.type, it.slug) }
        }
        logger.info(
            "Upgraded catalog '{}': {} -> {}{}",
            command.catalogKey,
            previousVersion,
            imported.releaseVersion,
            if (imported.aborted) " (aborted)" else ", ${removed.size} removed",
        )
        UpgradeCatalogResult(
            previousVersion = previousVersion,
            newVersion = imported.releaseVersion ?: previousVersion.orEmpty(),
            installResults = imported.results,
            removedResources = removed,
            aborted = imported.aborted,
        )
    }
}
