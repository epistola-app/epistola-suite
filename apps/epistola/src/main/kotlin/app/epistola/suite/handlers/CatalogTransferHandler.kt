// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.queries.FindResourceUsages
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.queries.PreviewInstall
import app.epistola.suite.handlers.CatalogViews.browseFragment
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/** Exporting a catalog as an archive and installing a release from a catalog listing. */
@Component
class CatalogTransferHandler {
    // Logs under CatalogHandler, the name these lines had before the handler was split.
    private val logger = LoggerFactory.getLogger(CatalogHandler::class.java)

    fun resourceUsages(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        val resourceType = request.param("type").orElse("")
        val resourceSlug = request.param("slug").orElse("")
        val key = "$resourceType:$resourceSlug"

        val usages = FindResourceUsages(catalog = catalog).query()
        val resourceUsages = usages[key] ?: emptyList()

        return fragmentOrPage(
            request,
            "catalogs/browse",
            "usage-detail",
            mapOf(
                "resourceType" to resourceType,
                "resourceSlug" to resourceSlug,
                "usages" to resourceUsages,
            ),
        )
    }

    fun installPreview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        val catalogKey = catalog.key

        return try {
            val preview = PreviewInstall(
                catalog = catalog,
            ).query()

            fragmentOrPage(
                request,
                "catalogs/browse",
                "install-preview",
                mapOf(
                    "tenantId" to tenantId.key,
                    "catalog" to mapOf("id" to catalogKey.value, "address" to catalog.address),
                    "allResources" to preview.resources,
                ),
            )
        } catch (e: Exception) {
            logger.warn("Failed to preview install: ${e.message}", e)
            fragmentOrPage(request, "catalogs/browse", "install-preview-error", mapOf("error" to (e.message ?: "Failed to resolve dependencies.")))
        }
    }

    fun install(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)

        return try {
            val results = InstallFromCatalog(
                catalog = catalog,
            ).execute()

            val failed = results.filter { it.status == InstallStatus.FAILED }
            val message = if (failed.isNotEmpty()) {
                null to "Failed to install: ${failed.joinToString(", ") { it.slug }}"
            } else {
                val installed = results.count { it.status == InstallStatus.INSTALLED }
                val updated = results.count { it.status == InstallStatus.UPDATED }
                val parts = listOfNotNull(
                    if (installed > 0) "$installed installed" else null,
                    if (updated > 0) "$updated updated" else null,
                )
                "Resources ${parts.joinToString(", ")}." to null
            }

            browseFragment(request, catalog, message.first, message.second)
        } catch (e: Exception) {
            logger.warn("Failed to install from catalog: ${e.message}", e)
            browseFragment(request, catalog, errorMessage = e.message ?: "Failed to install resources from catalog.")
        }
    }

    /** The export dialog: the working copy, or a release that kept its content, as it was released. */
    fun exportCheck(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        return fragmentOrPage(
            request,
            "catalogs/list",
            "export-confirmation-dialog",
            mapOf(
                "tenantId" to tenantId.key,
                "catalogId" to catalog.address,
                // Every release can be handed over exactly as released.
                "releases" to ListCatalogReleaseSummaries(catalog).query().map { it.version },
            ),
        )
    }

    fun export(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)

        return try {
            val result = ExportCatalogZip(
                catalog = catalog,
                version = request.param("version").orElse(null)?.ifBlank { null },
                // A fat export: one file with the releases of the catalogs it uses, to install elsewhere.
                includeDependencies = request.param("includeDependencies").orElse("") == "true",
            ).execute()

            ServerResponse.ok()
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename=\"${result.filename}\"")
                .body(result.zipBytes)
        } catch (e: Exception) {
            logger.warn("Failed to export catalog: ${e.message}", e)
            ServerResponse.badRequest()
                .header("Content-Type", "application/json")
                .body(mapOf("error" to (e.message ?: "Failed to export catalog")))
        }
    }
}
