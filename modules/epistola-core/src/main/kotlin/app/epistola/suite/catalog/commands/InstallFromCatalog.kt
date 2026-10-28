// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
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
import org.springframework.stereotype.Component

/**
 * Installs a subscribed catalog's current release from its source URL.
 *
 * Installing is importing (contract ADR 0008): the source assembles the release it offers into an
 * archive ([ManifestUrlReleaseSource]), and [ImportCatalogZip] receives it exactly as an uploaded
 * archive, a registry or the Exchange is received. So its fingerprint is recomputed, every binary is
 * checked against its hash, its pins must be present, and it is kept as it says it is or refused.
 * A catalog is one install unit: all of the release or nothing (issue #850).
 */
data class InstallFromCatalog(
    val catalog: CatalogId,
) : Command<List<InstallResult>>,
    RequiresPermission,
    // Downloads catalog content over HTTP mid-command.
    SelfManagedTransaction {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_EDIT
}

data class InstallResult(
    val type: String,
    val slug: String,
    val status: InstallStatus,
    val errorMessage: String? = null,
)

enum class InstallStatus {
    INSTALLED,
    UPDATED,
    SKIPPED,
    FAILED,
}

@Component
class InstallFromCatalogHandler(
    private val source: ManifestUrlReleaseSource,
) : CommandHandler<InstallFromCatalog, List<InstallResult>> {

    override fun handle(command: InstallFromCatalog): List<InstallResult> = CatalogImportContext.runAsImport {
        installFromSource(source, command.catalog).results
    }
}

/**
 * Receives the release [catalog]'s source URL offers now, through the archive path every other way of
 * receiving one takes. Without changes of its own the next version moves to it, and what the publisher
 * no longer publishes goes, subject to the conflict check; with changes, the release is recorded beside
 * the next version, to be merged (ADR 0032).
 */
internal fun installFromSource(source: ManifestUrlReleaseSource, catalog: CatalogId, provenance: app.epistola.suite.catalog.releases.ReleaseProvenance? = null): ImportCatalogZipResult {
    val existing = GetCatalog(catalog).query()
        ?: throw IllegalArgumentException("Catalog not found: ${catalog.key.value}")
    requireNotNull(existing.sourceUrl) { "Catalog has no source URL: ${catalog.key.value}" }
    val version = source.offered(existing).single()
    return source.install(existing, version, provenance ?: source.provenance(existing))
}

/**
 * Thrown when a catalog's content exceeds the budget an install may hold in memory.
 *
 * The same ceiling a ZIP import enforces (`epistola.catalog.max-decompressed-size`), applied to the
 * URL-subscribed path so a catalog that would be refused as an archive is not accepted as a source.
 */
class CatalogTooLargeException(
    val catalogKey: CatalogKey,
    val fetchedBytes: Long,
    val budgetBytes: Long,
) : RuntimeException(
    "Catalog '${catalogKey.value}' content exceeds the ${budgetBytes / (1024 * 1024)} MB install budget " +
        "(reached ${fetchedBytes / (1024 * 1024)} MB). Ask the publisher to split it into smaller catalogs, " +
        "or raise epistola.catalog.max-decompressed-size.",
)

/**
 * Thrown when a catalog references dependencies that are not included in the manifest.
 */
class InvalidCatalogException(message: String) : RuntimeException(message)
