// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.commands.CatalogUpgradeConflictException
import app.epistola.suite.catalog.commands.CheckCatalogUpstream
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.sources.InstallCatalogRelease
import app.epistola.suite.catalog.sources.ListAvailableCatalogReleases
import app.epistola.suite.exchange.ExchangeSourceUri
import app.epistola.suite.handlers.CatalogViews.catalogListModel
import app.epistola.suite.handlers.CatalogViews.refusedDialog
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/** Upgrading a subscribed catalog to a newer release of its source. */
@Component
class CatalogUpgradeHandler {
    // Logs under CatalogHandler, the name these lines had before the handler was split.
    private val logger = LoggerFactory.getLogger(CatalogHandler::class.java)

    /**
     * The per-row "check for updates" button.
     *
     * Asks the source now and records the answer, so the next page load already knows it and the
     * background check does not immediately re-ask. Returns the same fragment the list renders from,
     * which is what keeps one definition of the Version cell.
     */
    fun upgradeCheck(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalog(request.tenantId())
        val catalogKey = catalogId.key
        val catalog = GetCatalog(catalogId).query()
            ?: return fragmentOrPage(request, "catalogs/list", "version-status", mapOf("tenantId" to tenantId.key, "catalogId" to catalogId.address, "status" to null))

        val check = try {
            CheckCatalogUpstream(catalogId).execute()
        } catch (e: Exception) {
            logger.warn("Upgrade check failed for catalog {}: {}", catalogKey, e.message)
            null
        }
        val status = CatalogReleaseStatusView.of(
            catalog,
            check,
            CATALOG_SCHEMA_VERSION,
            fromExchange = ExchangeSourceUri.matches(catalog.sourceUrl),
        )
        return fragmentOrPage(
            request,
            "catalogs/list",
            "version-status",
            mapOf(
                "tenantId" to tenantId.key,
                "catalogId" to catalogId.address,
                "status" to status,
            ),
        )
    }

    private fun upgradeDialog(request: ServerRequest, catalog: app.epistola.suite.common.ids.CatalogId, error: String? = null, refused: Boolean = false): ServerResponse {
        val tenantId = catalog.tenantId
        val catalogKey = catalog.key
        val model = HashMap<String, Any?>()
        model["tenantId"] = tenantId.key
        model["catalogId"] = catalogKey.value
        model["error"] = error
        try {
            model["available"] = ListAvailableCatalogReleases(catalog).query()
        } catch (e: Exception) {
            logger.warn("Listing the releases available for catalog {} failed: {}", catalogKey, e.message)
            model["available"] = null
            if (error == null) model["error"] = e.message ?: "The catalog's source could not be asked. It may be unavailable."
        }
        // A refused upgrade says so with a 422; the preview is a plain 200.
        if (refused) return refusedDialog(request, "upgrade-dialog", model)
        return fragmentOrPage(request, "catalogs/list", "upgrade-dialog", model)
    }

    fun upgradePreview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        return upgradeDialog(request, catalog)
    }

    fun upgrade(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)

        val releaseVersion = request.param("releaseVersion").orElse("").trim().ifEmpty { null }
            ?: return upgradeDialog(request, catalog, "Choose a release to install.", refused = true)

        return try {
            val result = InstallCatalogRelease(catalog, releaseVersion).execute()

            if (result.aborted) {
                val failed = result.results
                    .filter { it.status == InstallStatus.FAILED }
                    .joinToString(", ") { "${it.type}/${it.slug}" }
                return upgradeDialog(request, catalog, "Installing v$releaseVersion stopped: these resources failed to install, and nothing was changed: $failed", refused = true)
            }

            request.htmx {
                fragment("catalogs/list", "upgrade-done") {}
                oob("catalogs/list", "catalog-list") {
                    catalogListModel(request)
                    "oob" to true
                }
                onFullPage {
                    redirect("/tenants/${tenantId.key}/catalogs?saved=true")
                }
            }
        } catch (e: CatalogUpgradeConflictException) {
            upgradeDialog(request, catalog, e.message ?: "Installing it is blocked: resources are still in use.", refused = true)
        } catch (e: Exception) {
            logger.warn("Failed to install a release of catalog: ${e.message}", e)
            upgradeDialog(request, catalog, e.message ?: "Failed to install the release.", refused = true)
        }
    }
}
