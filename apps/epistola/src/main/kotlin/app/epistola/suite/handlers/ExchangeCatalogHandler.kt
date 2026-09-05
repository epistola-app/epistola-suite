// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.exchange.ExchangeSourceUri
import app.epistola.suite.exchange.GetExchangeCatalogDetail
import app.epistola.suite.exchange.InstallExchangeCatalog
import app.epistola.suite.exchange.SearchExchangeCatalogs
import app.epistola.suite.exchange.UpgradeExchangeCatalog
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Browsing Epistola Exchange and installing from it.
 *
 * Its own handler rather than more of [CatalogHandler], which is already long enough that finding
 * anything in it is a chore. Mounted under `/catalogs/` all the same, so the navigation keeps
 * highlighting Catalogs and installing reads as what it is: another way to get a catalog, beside
 * subscribing to a URL and importing a ZIP.
 *
 * Both permissions are required to install. [Permission.CATALOG_MANAGE] is the catalog-lifecycle
 * one, and the nested ZIP import carries [Permission.TEMPLATE_EDIT] — checked here so someone
 * holding only one is told before a release is downloaded rather than after.
 */
@Component
class ExchangeCatalogHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun browse(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CATALOG_VIEW)
        val query = request.param("q").orElse(null)
        return ServerResponse.ok().page("catalogs/exchange-browse") {
            "pageTitle" to "Browse Exchange - Epistola"
            "activeNavSection" to "catalogs"
            "tenantId" to tenantKey
            "q" to query
            "result" to SearchExchangeCatalogs(tenantKey, query).query()
            "canInstall" to canInstall(tenantKey)
        }
    }

    /** The results list alone, for the search box's debounced `hx-get`. */
    fun search(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CATALOG_VIEW)
        val query = request.param("q").orElse(null)
        return ServerResponse.ok().render(
            "catalogs/exchange-browse :: results",
            mapOf(
                "tenantId" to tenantKey,
                "q" to query,
                "result" to SearchExchangeCatalogs(tenantKey, query).query(),
                "canInstall" to canInstall(tenantKey),
            ),
        )
    }

    /** One catalog and the releases of it this tenant may install. */
    fun detail(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CATALOG_VIEW)
        val namespace = request.pathVariable("namespace")
        val catalogKey = request.pathVariable("catalogKey")
        val detail = GetExchangeCatalogDetail(tenantKey, namespace, catalogKey).query()
            ?: return installError(
                request,
                "Epistola Exchange does not offer $namespace/$catalogKey, or this tenant may not see it.",
            )
        return ServerResponse.ok().render(
            "catalogs/exchange-browse :: detail-dialog",
            mapOf(
                "tenantId" to tenantKey,
                "detail" to detail,
                // What the local catalog id would be, so the dialog can say so before anyone
                // discovers it by colliding with something.
                "localCatalogId" to catalogKey,
                "existing" to GetCatalog(tenantKey, CatalogKey.of(catalogKey)).query(),
                "canInstall" to canInstall(tenantKey),
            ),
        )
    }

    fun install(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requireInstallPermissions(tenantId.key)
        val namespace = request.pathVariable("namespace")
        val catalogKey = request.pathVariable("catalogKey")
        val version = request.param("version").orElse(null)?.ifBlank { null }

        return try {
            val result = InstallExchangeCatalog(tenantId.key, namespace, catalogKey, version).execute()
            if (result.aborted) {
                val failed = result.imported.results.filter { it.status == InstallStatus.FAILED }
                return installError(
                    request,
                    "Nothing was changed. ${failed.size} resource(s) in ${result.release.version} could not be " +
                        "installed, so the catalog was left exactly as it was: " +
                        failed.take(FAILED_RESOURCES_SHOWN).joinToString(", ") { "${it.type}/${it.slug}" },
                )
            }
            request.htmx {
                dialogSuccess("catalogs/list", "catalog-list", "/tenants/${tenantId.key}/catalogs") {
                    catalogListModel(tenantId.key)
                }
                onNonHtmx { redirect("/tenants/${tenantId.key}/catalogs?saved=true") }
            }
        } catch (e: ValidationException) {
            installError(request, e.message)
        } catch (e: Exception) {
            logger.warn("Installing {}/{} failed: {}", namespace, catalogKey, e.message, e)
            installError(request, "The catalog could not be installed from Epistola Exchange. ${e.message ?: ""}".trim())
        }
    }

    /**
     * The upgrade dialog for an Exchange catalog.
     *
     * Release metadata only, not the resource-by-resource diff a URL-subscribed catalog gets: that
     * diff is built by walking a manifest, and an Exchange release is an archive. What the dialog
     * can say instead is the guarantee — a failed resource abandons the whole upgrade — which is
     * the thing a reader actually needs before pressing the button.
     */
    fun upgradeDialog(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CATALOG_VIEW)
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val catalog = GetCatalog(tenantKey, catalogKey).query()
        val coordinates = ExchangeSourceUri.parse(catalog?.sourceUrl)
            ?: return installError(request, "This catalog was not installed from Epistola Exchange.")
        val detail = GetExchangeCatalogDetail(tenantKey, coordinates.namespace, coordinates.catalogKey).query()
            ?: return installError(request, "Epistola Exchange no longer offers $coordinates.")

        return ServerResponse.ok().render(
            "catalogs/exchange-browse :: upgrade-dialog",
            mapOf(
                "tenantId" to tenantKey,
                "catalogId" to catalogKey.value,
                "catalog" to catalog,
                "detail" to detail,
                "canInstall" to canInstall(tenantKey),
            ),
        )
    }

    fun upgrade(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requireInstallPermissions(tenantId.key)
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val version = request.param("version").orElse(null)?.ifBlank { null }

        return try {
            val result = UpgradeExchangeCatalog(tenantId.key, catalogKey, version).execute()
            if (result.aborted) {
                val failed = result.imported.results.filter { it.status == InstallStatus.FAILED }
                return installError(
                    request,
                    "Nothing was changed. ${failed.size} resource(s) in ${result.release.version} could not be " +
                        "installed, so the catalog stays on the version it was already running.",
                )
            }
            request.htmx {
                dialogSuccess("catalogs/list", "catalog-list", "/tenants/${tenantId.key}/catalogs") {
                    catalogListModel(tenantId.key)
                }
                onNonHtmx { redirect("/tenants/${tenantId.key}/catalogs?saved=true") }
            }
        } catch (e: ValidationException) {
            installError(request, e.message)
        } catch (e: Exception) {
            logger.warn("Upgrading {} failed: {}", catalogKey, e.message, e)
            installError(request, "The catalog could not be upgraded. ${e.message ?: ""}".trim())
        }
    }

    /**
     * A refusal renders into the dialog's own error slot and leaves it open.
     *
     * Replacing the dialog container instead would swap the dialog out for a bare message, taking
     * the version the reader had chosen with it — the same stay-on-dialog contract the subscribe
     * dialog already documents.
     */
    private fun installError(request: ServerRequest, message: String): ServerResponse = request.htmx {
        dialogFormError("exchange-install-error", message)
        onNonHtmx { redirect("/tenants/${request.tenantId().key}/catalogs") }
    }

    private fun canInstall(tenantKey: TenantKey): Boolean = SecurityContext.current().let { principal ->
        principal.hasPermission(tenantKey, Permission.CATALOG_MANAGE) &&
            principal.hasPermission(tenantKey, Permission.TEMPLATE_EDIT)
    }

    private fun requireInstallPermissions(tenantKey: TenantKey) {
        requirePermission(tenantKey, Permission.CATALOG_MANAGE)
        requirePermission(tenantKey, Permission.TEMPLATE_EDIT)
    }

    private companion object {
        const val FAILED_RESOURCES_SHOWN = 5
    }
}
