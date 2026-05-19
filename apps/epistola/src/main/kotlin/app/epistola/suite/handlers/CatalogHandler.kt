package app.epistola.suite.handlers

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CatalogUpgradeConflictException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UnregisterCatalog
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CheckCatalogUpgrade
import app.epistola.suite.catalog.queries.FindResourceUsages
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogReleaseStatus
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.queries.PreviewInstall
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class CatalogHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogs = ListCatalogs(tenantId.key).query()
        val saved = request.param("saved").isPresent

        return ServerResponse.ok().page("catalogs/list") {
            "pageTitle" to "Catalogs - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "catalogs"
            "catalogs" to catalogs
            if (saved) "saved" to true
        }
    }

    fun createCatalog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
            }
            field("name") {
                required()
            }
        }

        if (form.hasErrors()) {
            return listWithError(request, "Catalog slug and name are required.")
        }

        return try {
            CreateCatalog(
                tenantKey = tenantId.key,
                id = CatalogKey.of(form["slug"]),
                name = form["name"],
            ).execute()

            val catalogs = ListCatalogs(tenantId.key).query()
            request.htmx {
                fragment("catalogs/list", "catalog-list") {
                    "tenantId" to tenantId.key
                    "catalogs" to catalogs
                }
                onNonHtmx {
                    redirect("/tenants/${tenantId.key}/catalogs?saved=true")
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to create catalog: ${e.message}", e)
            listWithError(request, "Failed to create catalog. The slug may already be in use.")
        }
    }

    fun register(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("sourceUrl") {
                required()
                maxLength(2000)
            }
        }

        if (form.hasErrors()) {
            return listWithError(request, "Catalog URL is required.")
        }

        val sourceUrl = form.formData["sourceUrl"]!!
        val authTypeStr = form.formData["authType"] ?: "NONE"
        val authType = try {
            AuthType.valueOf(authTypeStr)
        } catch (_: IllegalArgumentException) {
            AuthType.NONE
        }
        val authCredential = form.formData["authCredential"]?.ifBlank { null }

        return try {
            RegisterCatalog(
                tenantKey = tenantId.key,
                sourceUrl = sourceUrl,
                authType = authType,
                authCredential = authCredential,
            ).execute()

            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs?saved=true")
                .build()
        } catch (e: Exception) {
            logger.warn("Failed to register catalog: ${e.message}", e)
            listWithError(request, "Failed to register catalog. Check that the URL points to a valid catalog manifest.")
        }
    }

    fun unregister(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        return try {
            val force = request.param("force").orElse("false").toBoolean()
            UnregisterCatalog(tenantKey = tenantId.key, catalogKey = catalogKey, force = force).execute()

            return request.htmx {
                val catalogs = ListCatalogs(tenantId.key).query()
                fragment("catalogs/list", "catalog-list") {
                    "tenantId" to tenantId.key
                    "catalogs" to catalogs
                }
                onNonHtmx {
                    ServerResponse.status(303)
                        .header("Location", "/tenants/${tenantId.key}/catalogs")
                        .build()
                }
            }
        } catch (e: app.epistola.suite.catalog.CatalogInUseException) {
            if (request.isHtmx) {
                ServerResponse.status(422)
                    .header("Content-Type", "application/json")
                    .body(mapOf("error" to (e.message ?: "Catalog is in use by other catalogs")))
            } else {
                listWithError(request, e.message ?: "Catalog is in use by other catalogs")
            }
        } catch (e: Exception) {
            logger.warn("Failed to unregister catalog: ${e.message}", e)
            if (request.isHtmx) {
                ServerResponse.status(422)
                    .header("Content-Type", "application/json")
                    .body(mapOf("error" to (e.message ?: "Failed to remove catalog.")))
            } else {
                listWithError(request, e.message ?: "Failed to remove catalog.")
            }
        }
    }

    fun releaseDialog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val status = GetCatalogReleaseStatus(tenantId.key, catalogKey).query()
        return ServerResponse.ok().render(
            "catalogs/list :: release-dialog",
            mapOf(
                "tenantId" to tenantId.key,
                "catalogId" to catalogKey.value,
                "status" to status,
            ),
        )
    }

    fun release(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        val form = request.form {
            field("version") {
                required()
                pattern("^\\d+\\.\\d+\\.\\d+$")
            }
        }

        fun reRenderWithError(message: String): ServerResponse {
            val status = GetCatalogReleaseStatus(tenantId.key, catalogKey).query()
            return ServerResponse.ok().render(
                "catalogs/list :: release-dialog",
                mapOf(
                    "tenantId" to tenantId.key,
                    "catalogId" to catalogKey.value,
                    "status" to status,
                    "error" to message,
                ),
            )
        }

        if (form.hasErrors()) {
            return reRenderWithError("Version must be SemVer — MAJOR.MINOR.PATCH (e.g. 1.4.0).")
        }

        val notes = form.formData["notes"]?.ifBlank { null }

        return try {
            ReleaseCatalogVersion(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
                version = form["version"],
                notes = notes,
            ).execute()

            val catalogs = ListCatalogs(tenantId.key).query()
            request.htmx {
                fragment("catalogs/list", "release-done") {}
                oob("catalogs/list", "catalog-list") {
                    "tenantId" to tenantId.key
                    "catalogs" to catalogs
                    "oob" to true
                }
                onNonHtmx {
                    redirect("/tenants/${tenantId.key}/catalogs?saved=true")
                }
            }
        } catch (e: CatalogReleaseVersionException) {
            reRenderWithError(e.message ?: "Invalid release version.")
        } catch (e: Exception) {
            logger.warn("Failed to release catalog: ${e.message}", e)
            reRenderWithError(e.message ?: "Failed to release catalog.")
        }
    }

    /**
     * Lazy per-row "upgrade available?" indicator (SUBSCRIBED only). Cheap —
     * manifest fetch only. Renders nothing when up to date or unreachable, so a
     * failed remote never breaks the list.
     */
    /**
     * Explicit per-row upgrade check (user clicks "Check for updates"). Returns
     * the `upgrade-indicator` fragment in one of:
     * `UP_TO_DATE` / `UPDATE_AVAILABLE` / `ZIP_MANAGED` / `CHECK_FAILED`.
     * Cheap — `CheckCatalogUpgrade` fetches only the manifest. ZIP-managed
     * (no source URL) catalogs can't be polled — they upgrade by re-importing
     * a newer ZIP, so we say so instead of erroring.
     */
    fun upgradeCheck(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val catalog = GetCatalog(tenantId.key, catalogKey).query()

        val model = HashMap<String, Any?>()
        model["tenantId"] = tenantId.key
        model["catalogId"] = catalogKey.value
        model["installedVersion"] = catalog?.installedReleaseVersion
        model["availableVersion"] = null

        when {
            catalog == null -> model["state"] = "CHECK_FAILED"
            catalog.sourceUrl == null -> model["state"] = "ZIP_MANAGED"
            else -> try {
                val a = CheckCatalogUpgrade(tenantId.key, catalogKey).query()
                model["state"] = if (a.available) "UPDATE_AVAILABLE" else "UP_TO_DATE"
                model["installedVersion"] = a.installedVersion
                model["availableVersion"] = a.availableVersion
            } catch (e: Exception) {
                logger.warn("Upgrade check failed for catalog {}: {}", catalogKey, e.message)
                model["state"] = "CHECK_FAILED"
            }
        }
        return ServerResponse.ok().render("catalogs/list :: version-status", model)
    }

    private fun upgradeDialog(tenantId: app.epistola.suite.common.ids.TenantId, catalogKey: CatalogKey, error: String? = null): ServerResponse {
        val model = HashMap<String, Any?>()
        model["tenantId"] = tenantId.key
        model["catalogId"] = catalogKey.value
        model["error"] = error
        try {
            model["diff"] = PreviewCatalogUpgrade(tenantId.key, catalogKey).query()
        } catch (e: Exception) {
            logger.warn("Upgrade preview failed for catalog {}: {}", catalogKey, e.message)
            model["diff"] = null
            if (error == null) model["error"] = e.message ?: "Failed to preview upgrade. The remote catalog may be unavailable."
        }
        return ServerResponse.ok().render("catalogs/list :: upgrade-dialog", model)
    }

    fun upgradePreview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        return upgradeDialog(tenantId, catalogKey)
    }

    fun upgrade(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val includeNewSlugs = request.servletRequest().getParameterValues("newSlugs")?.toList() ?: emptyList()

        return try {
            val result = UpgradeCatalog(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
                includeNewSlugs = includeNewSlugs,
            ).execute()

            if (result.aborted) {
                val failed = result.installResults
                    .filter { it.status == InstallStatus.FAILED }
                    .joinToString(", ") { "${it.type}/${it.slug}" }
                return upgradeDialog(tenantId, catalogKey, "Upgrade aborted — these resources failed to install (nothing was changed, the version was not advanced): $failed")
            }

            val catalogs = ListCatalogs(tenantId.key).query()
            request.htmx {
                fragment("catalogs/list", "upgrade-done") {}
                oob("catalogs/list", "catalog-list") {
                    "tenantId" to tenantId.key
                    "catalogs" to catalogs
                    "oob" to true
                }
                onNonHtmx {
                    redirect("/tenants/${tenantId.key}/catalogs?saved=true")
                }
            }
        } catch (e: CatalogUpgradeConflictException) {
            upgradeDialog(tenantId, catalogKey, e.message ?: "Upgrade blocked — resources are still in use.")
        } catch (e: Exception) {
            logger.warn("Failed to upgrade catalog: ${e.message}", e)
            upgradeDialog(tenantId, catalogKey, e.message ?: "Failed to upgrade catalog.")
        }
    }

    fun browse(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        return try {
            val result = BrowseCatalog(tenantKey = tenantId.key, catalogKey = catalogKey).query()
            val usages = FindResourceUsages(tenantKey = tenantId.key, catalogKey = catalogKey).query()
            val usageCounts = usages.mapValues { it.value.size }

            ServerResponse.ok().page("catalogs/browse") {
                "pageTitle" to "${result.catalog.name} - Catalog - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "catalogs"
                "catalog" to result.catalog
                "resources" to result.resources
                "usageCounts" to usageCounts
            }
        } catch (e: Exception) {
            logger.warn("Failed to browse catalog: ${e.message}", e)
            listWithError(request, "Failed to fetch catalog. The remote server may be unavailable or the URL may be incorrect.")
        }
    }

    fun resourceUsages(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val resourceType = request.param("type").orElse("")
        val resourceSlug = request.param("slug").orElse("")
        val key = "$resourceType:$resourceSlug"

        val usages = FindResourceUsages(tenantKey = tenantId.key, catalogKey = catalogKey).query()
        val resourceUsages = usages[key] ?: emptyList()

        return ServerResponse.ok().render(
            "catalogs/browse :: usage-detail",
            mapOf(
                "resourceType" to resourceType,
                "resourceSlug" to resourceSlug,
                "usages" to resourceUsages,
            ),
        )
    }

    fun installPreview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val slugParam = request.param("slug").orElse(null)
        val resourceSlugs = slugParam?.let { listOf(it) }

        return try {
            val preview = PreviewInstall(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
                resourceSlugs = resourceSlugs,
            ).query()

            ServerResponse.ok().render(
                "catalogs/browse :: install-preview",
                mapOf(
                    "tenantId" to tenantId.key,
                    "catalog" to mapOf("id" to catalogKey.value),
                    "selected" to preview.selected,
                    "dependencies" to preview.dependencies,
                    "allResources" to preview.all,
                    "slugParam" to (slugParam ?: ""),
                ),
            )
        } catch (e: Exception) {
            logger.warn("Failed to preview install: ${e.message}", e)
            ServerResponse.ok().render(
                "catalogs/browse :: install-preview-error",
                mapOf("error" to (e.message ?: "Failed to resolve dependencies.")),
            )
        }
    }

    fun install(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        val slugParam = request.param("slug").orElse(null)?.ifBlank { null }
        val resourceSlugs = slugParam?.let { listOf(it) }

        return try {
            val results = InstallFromCatalog(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
                resourceSlugs = resourceSlugs,
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

            browseFragment(request, catalogKey, message.first, message.second)
        } catch (e: Exception) {
            logger.warn("Failed to install from catalog: ${e.message}", e)
            browseFragment(request, catalogKey, errorMessage = e.message ?: "Failed to install resources from catalog.")
        }
    }

    private fun browseFragment(
        request: ServerRequest,
        catalogKey: CatalogKey,
        successMessage: String? = null,
        errorMessage: String? = null,
    ): ServerResponse {
        val tenantId = request.tenantId()
        val result = BrowseCatalog(tenantKey = tenantId.key, catalogKey = catalogKey).query()

        return request.htmx {
            fragment("catalogs/browse", "resource-rows") {
                "resources" to result.resources
                "tenantId" to tenantId.key
                "catalog" to result.catalog
            }
            oob("catalogs/browse", "alert") {
                if (successMessage != null) "successMessage" to successMessage
                if (errorMessage != null) "errorMessage" to errorMessage
            }
            trigger("installComplete")
            onNonHtmx {
                page("catalogs/browse") {
                    "pageTitle" to "${result.catalog.name} - Catalog - Epistola"
                    "tenantId" to tenantId.key
                    "activeNavSection" to "catalogs"
                    "catalog" to result.catalog
                    "resources" to result.resources
                    if (errorMessage != null) "error" to errorMessage
                }
            }
        }
    }

    fun importZip(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val multipartData = request.multipartData()
        val filePart = multipartData["file"]?.firstOrNull()
            ?: return importError(request, "No file provided")

        val zipBytes = filePart.inputStream.use { it.readAllBytes() }

        val catalogTypeStr = multipartData["catalogType"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim()
        } ?: "AUTHORED"
        val catalogType = try {
            CatalogType.valueOf(catalogTypeStr)
        } catch (_: Exception) {
            return importError(request, "Invalid catalog type: $catalogTypeStr")
        }

        return try {
            val result = ImportCatalogZip(
                tenantKey = tenantId.key,
                zipBytes = zipBytes,
                catalogType = catalogType,
            ).execute()

            val failed = result.results.count { it.status == InstallStatus.FAILED }
            val installed = result.results.count { it.status == InstallStatus.INSTALLED }
            val updated = result.results.count { it.status == InstallStatus.UPDATED }

            val message = if (failed > 0) {
                "Imported catalog '${result.catalogName}' with $failed failures."
            } else {
                val parts = listOfNotNull(
                    if (installed > 0) "$installed installed" else null,
                    if (updated > 0) "$updated updated" else null,
                )
                "Imported catalog '${result.catalogName}': ${parts.joinToString(", ")}."
            }

            if (request.isHtmx) {
                ServerResponse.ok()
                    .header("HX-Redirect", "/tenants/${tenantId.key}/catalogs/${result.catalogKey}/browse")
                    .build()
            } else {
                ServerResponse.status(303)
                    .header("Location", "/tenants/${tenantId.key}/catalogs/${result.catalogKey}/browse")
                    .build()
            }
        } catch (e: Exception) {
            logger.warn("Failed to import catalog from ZIP: ${e.message}", e)
            importError(request, e.message ?: "Failed to import catalog")
        }
    }

    private fun importError(request: ServerRequest, error: String): ServerResponse {
        if (request.isHtmx) {
            val escaped = error.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return ServerResponse.ok()
                .header("Content-Type", "text/html")
                .body("""<div class="alert alert-danger">$escaped</div>""")
        }
        return listWithError(request, error)
    }

    fun export(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        return try {
            val result = ExportCatalogZip(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
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

    private fun listWithError(request: ServerRequest, error: String): ServerResponse {
        val tenantId = request.tenantId()
        val catalogs = ListCatalogs(tenantId.key).query()

        return ServerResponse.ok().page("catalogs/list") {
            "pageTitle" to "Catalogs - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "catalogs"
            "catalogs" to catalogs
            "error" to error
        }
    }
}
