// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.catalog.RESOURCE_INSTALL_ORDER
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.execute
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** What installing an archive's resources gave: each one's result, and what each bound to. */
internal data class InstalledArchiveResources(val results: List<InstallResult>, val bound: Map<String, CatalogResource>)

/**
 * Installs an import's resources into its catalog, in dependency order, each through the per-type
 * installer ([CatalogResourceInstaller]). A received release installs them in a savepoint of its own.
 */
@Component
class CatalogArchiveResources(
    private val schemaMigrator: CatalogSchemaMigrator,
    private val installer: CatalogResourceInstaller,
    transactionManager: org.springframework.transaction.PlatformTransactionManager,
) {
    /** A savepoint inside the command's transaction, for a received release's resources. */
    private val resourcesSavepoint = org.springframework.transaction.support.TransactionTemplate(transactionManager).apply {
        propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED
    }

    // Logs under the import handler, the name these lines had before it was split.
    private val logger = LoggerFactory.getLogger(ImportCatalogZipHandler::class.java)

    internal fun install(catalog: CatalogId, parsed: ParsedCatalogArchive, receiving: Boolean): InstalledArchiveResources {
        val (entries, manifest, catalogCtx) = parsed
        val ordered = manifest.resources
            .sortedBy { RESOURCE_INSTALL_ORDER[it.type] ?: 99 }

        // Pre-parse every stencil detail once. Each detail is routed through the
        // migrator (catalog-wide version gate + chain) before binding; the chain
        // is empty today, so current-shape details pass through unchanged.
        // Missing / corrupt stencil JSON is still a hard import failure before any
        // mutation, and the parsed StencilResource objects are reused by the
        // install loop below so each stencil detail is deserialized only once.
        // Other resource types stay in the per-resource try/catch so a
        // missing/corrupt detail surfaces as a single failed install rather than
        // aborting the whole import — but a wire-version gate failure
        // (CatalogSchemaException) is rethrown there to reject the whole import,
        // matching the manifest gate. Stencil-version conflicts remain the only
        // deliberate group-decision (FAIL vs RENUMBER).
        val parsedStencilsBySlug: Map<String, StencilResource> = ordered
            .filter { it.type == "stencil" }
            .associate { entry ->
                val detailBytes = entries[entry.detailUrl.removePrefix("./")]
                    ?: throw IllegalArgumentException("Missing resource detail: ${entry.detailUrl}")
                val parsed = schemaMigrator.migrateAndBindResourceDetail(entry.type, detailBytes, catalogCtx).resource
                val stencil = parsed as? StencilResource
                    ?: throw IllegalArgumentException(
                        "Resource at ${entry.detailUrl} declared type 'stencil' but parsed as ${parsed::class.simpleName}",
                    )
                entry.key to stencil
            }
        // Install resources in dependency order. What each binds to is kept as well: a received
        // release is recorded from the archive as published, not from the rows it is put in place as.
        val bound = linkedMapOf<String, CatalogResource>()
        // A received release is all or nothing (issue #850): its resources install in a savepoint of
        // their own, rolled back when any one fails, so an aborted receive leaves none of them behind.
        val results = requireNotNull(
            resourcesSavepoint.execute { savepoint ->
                ordered.map { entry ->
                    try {
                        val resource: CatalogResource = if (entry.type == "stencil") {
                            parsedStencilsBySlug[entry.key]
                                ?: error("Pre-scan produced no stencil for slug '${entry.key}' (manifest/pre-scan out of sync)")
                        } else {
                            val detailPath = entry.detailUrl.removePrefix("./")
                            val detailBytes = entries[detailPath]
                                ?: throw IllegalArgumentException("Missing resource detail: ${entry.detailUrl}")
                            schemaMigrator.migrateAndBindResourceDetail(entry.type, detailBytes, catalogCtx).resource
                        }
                        bound["${entry.type}/${entry.key}"] = resource

                        val status = installer.install(
                            catalog = catalog,
                            resource = resource,
                            version = manifest.release.version,
                            binaries = entries::get,
                            declarations = manifest.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of),
                        )
                        InstallResult(type = entry.type, slug = entry.key, status = status)
                    } catch (e: CatalogSchemaException) {
                        // A wire-version gate failure (too new/old/unknown) rejects the
                        // whole import and surfaces the dedicated operator remediation
                        // (UI fragment / RFC 9457 problem) — never downgraded to a single
                        // FAILED resource by the generic catch below.
                        throw e
                    } catch (e: Exception) {
                        logger.error("Failed to import {} '{}': {}", entry.type, entry.key, e.message, e)
                        InstallResult(type = entry.type, slug = entry.key, status = InstallStatus.FAILED, errorMessage = e.message)
                    }
                }.also { results -> if (receiving && results.any { it.status == InstallStatus.FAILED }) savepoint.setRollbackOnly() }
            },
        )
        return InstalledArchiveResources(results, bound)
    }
}
