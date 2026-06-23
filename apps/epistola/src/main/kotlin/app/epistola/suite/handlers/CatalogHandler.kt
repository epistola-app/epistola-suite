package app.epistola.suite.handlers

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.MultipleStencilVersionsInUseException
import app.epistola.suite.catalog.commands.AuthoredImportMode
import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CatalogUpgradeConflictException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.OnStencilConflict
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.StencilVersionImportConflictsException
import app.epistola.suite.catalog.commands.UnregisterCatalog
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CatalogListRow
import app.epistola.suite.catalog.queries.CheckCatalogUpgrade
import app.epistola.suite.catalog.queries.FindResourceUsages
import app.epistola.suite.catalog.queries.FindStencilVersionExportConflicts
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogReleaseStatus
import app.epistola.suite.catalog.queries.ListCatalogsForManagement
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.queries.PreviewInstall
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.listParam
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.util.UriComponentsBuilder

@Component
class CatalogHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Unified list endpoint: full page on a normal request, the `#catalog-list` fragment on
     * an htmx search/sort request. Search + sort state lives in (and is pushed back to) the
     * query string, so the view is bookmarkable and survives a refresh. No pagination — the
     * catalog count per tenant is small — so the table sorts but has no footer.
     */
    fun list(request: ServerRequest): ServerResponse {
        val saved = request.param("saved").isPresent

        return request.htmx {
            fragment("catalogs/list", "catalog-list") {
                catalogListModel(request)
            }
            pushUrl(catalogListSort(request).canonicalUrl())
            onNonHtmx {
                page("catalogs/list") {
                    "pageTitle" to "Catalogs - Epistola"
                    "activeNavSection" to "catalogs"
                    catalogListModel(request)
                    if (saved) "saved" to true
                }
            }
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

            request.htmx {
                fragment("catalogs/list", "catalog-list") {
                    catalogListModel(request)
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
                fragment("catalogs/list", "catalog-list") {
                    catalogListModel(request)
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

            request.htmx {
                fragment("catalogs/list", "release-done") {}
                oob("catalogs/list", "catalog-list") {
                    catalogListModel(request)
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

            request.htmx {
                fragment("catalogs/list", "upgrade-done") {}
                oob("catalogs/list", "catalog-list") {
                    catalogListModel(request)
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
            // Per-stencil version-conflict map (slug → "pinned v1, v2 (latest v3)").
            // Empty when the catalog is exportable. Used by the browse view to flag
            // stencils that block export — mirrors the precheck the export endpoint
            // runs. The check also fires when templates pin a single stale version
            // (not just multi-version usage), hence the explicit `latest` callout.
            val stencilVersionConflicts = FindStencilVersionExportConflicts(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
            ).query().associate { c ->
                c.stencilKey.value to
                    "pinned v${c.versions.joinToString(", v")} (latest v${c.latestPublishedVersion})"
            }

            ServerResponse.ok().page("catalogs/browse") {
                "pageTitle" to "${result.catalog.name} - Catalog - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "catalogs"
                "catalog" to result.catalog
                "resources" to result.resources
                "usageCounts" to usageCounts
                "stencilVersionConflicts" to stencilVersionConflicts
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

        // Only consulted when the catalog already exists as AUTHORED; ignored
        // for a new catalog or SUBSCRIBED. MERGE = keep local-only resources;
        // REPLACE = mirror the ZIP (delete local-only, conflict-checked).
        val authoredMode = multipartData["authoredMode"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim()
        }?.let { runCatching { AuthoredImportMode.valueOf(it) }.getOrNull() }
            ?: AuthoredImportMode.MERGE

        // Opt-in: install conflicting stencil versions at MAX(version)+1 and
        // rewrite stencil-node pins in the same ZIP's templates. Only meaningful
        // for AUTHORED MERGE — the ImportCatalogZip handler rejects it elsewhere.
        val onStencilConflict = multipartData["onStencilConflict"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim()
        }?.let { runCatching { OnStencilConflict.valueOf(it) }.getOrNull() }
            ?: OnStencilConflict.FAIL

        return try {
            val result = ImportCatalogZip(
                tenantKey = tenantId.key,
                zipBytes = zipBytes,
                catalogType = catalogType,
                authoredMode = authoredMode,
                onStencilConflict = onStencilConflict,
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
        } catch (e: StencilVersionImportConflictsException) {
            // Render a structured dialog listing every conflicting stencil
            // version so the operator can see the full picture and choose
            // whether to retry with the "Allow renumber" option enabled.
            logger.warn("Import blocked by stencil-version conflicts: ${e.message}")
            val conflicts = e.conflicts.map { c ->
                StencilImportConflictView(
                    name = c.stencilName,
                    slug = c.stencilKey.value,
                    version = "v${c.version}",
                )
            }
            ServerResponse.ok().render(
                "catalogs/list :: import-conflict-content",
                mapOf(
                    "catalogId" to e.catalogKey.value,
                    "stencilImportConflicts" to conflicts,
                ),
            )
        } catch (e: CatalogSchemaException) {
            // The uploaded catalog's wire format is too new, too old, or
            // unrecognised — the migrator rejected it before binding. Render the
            // actionable remediation message inline (same alert-error slot as the
            // stencil-conflict report), so the operator knows whether to upgrade
            // this instance or re-export from a current source.
            logger.warn("Import blocked by catalog wire-format version: ${e.message}")
            val title = when (e) {
                is CatalogSchemaTooNewException -> "Import blocked: catalog format is too new"
                is CatalogSchemaTooOldException -> "Import blocked: catalog format is too old"
                is CatalogSchemaUnknownException -> "Import blocked: unrecognised catalog format"
            }
            ServerResponse.ok().render(
                "catalogs/list :: import-schema-error",
                mapOf(
                    "schemaErrorTitle" to title,
                    "schemaErrorDetail" to (e.message ?: "Incompatible catalog wire format."),
                ),
            )
        } catch (e: Exception) {
            logger.warn("Failed to import catalog from ZIP: ${e.message}", e)
            importError(request, e.message ?: "Failed to import catalog")
        }
    }

    data class StencilImportConflictView(
        val name: String,
        val slug: String,
        val version: String,
    )

    private fun importError(request: ServerRequest, error: String): ServerResponse {
        if (request.isHtmx) {
            val escaped = error.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return ServerResponse.ok()
                .header("Content-Type", "text/html")
                .body("""<div class="alert alert-danger">$escaped</div>""")
        }
        return listWithError(request, error)
    }

    /**
     * HTMX precheck for the export button. Runs the cheap conflict query and
     * either:
     *  - returns the export-conflict dialog fragment when conflicts exist;
     *  - returns `HX-Redirect` to the real export URL so the browser starts the
     *    ZIP download. The `MultipleStencilVersionsInUseException` thrown by
     *    [ExportCatalogZip] is the backstop for non-UI callers (REST, MCP).
     */
    fun exportCheck(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        val conflicts = FindStencilVersionExportConflicts(tenantId.key, catalogKey).query()
        if (conflicts.isEmpty()) {
            return ServerResponse.noContent()
                .header("HX-Redirect", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/export")
                .build()
        }

        val model = mapOf(
            "tenantId" to tenantId.key,
            "catalogId" to catalogKey.value,
            "stencilVersionExportConflicts" to conflicts.map { s ->
                StencilConflictView(
                    name = s.stencilName,
                    slug = s.stencilKey.value,
                    versionsDisplay = s.versions.joinToString(", ") { "v$it" },
                    latestPublishedVersion = "v${s.latestPublishedVersion}",
                )
            },
        )
        return ServerResponse.ok().render("catalogs/list :: export-conflict-dialog", model)
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
        } catch (e: MultipleStencilVersionsInUseException) {
            // The UI calls `exportCheck` first, so this path is only reached
            // when a non-UI client (REST/MCP) hits /export directly without
            // having run the precheck. Surface a plain JSON 400.
            logger.warn("Export blocked: ${e.message}")
            ServerResponse.badRequest()
                .header("Content-Type", "application/json")
                .body(mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.warn("Failed to export catalog: ${e.message}", e)
            ServerResponse.badRequest()
                .header("Content-Type", "application/json")
                .body(mapOf("error" to (e.message ?: "Failed to export catalog")))
        }
    }

    data class StencilConflictView(
        val name: String,
        val slug: String,
        val versionsDisplay: String,
        val latestPublishedVersion: String,
    )

    /**
     * The shared model for **every** render of the catalog list — the full
     * page *and* the `catalog-list` fragment/OOB swaps. One source of truth so
     * no render path can forget the AUTHORED drift hint:
     *  - `tenantId`;
     *  - `catalogs` — `List<CatalogListRow>` from `ListCatalogsForManagement`:
     *    each row is a `Catalog` plus the list-only `pendingChanges` flag
     *    (AUTHORED working copy drifted since the last release/import),
     *    computed in one SQL join. No parallel id set, no template-side
     *    cross-reference, no content build.
     */
    private fun ModelBuilder.catalogListModel(request: ServerRequest) {
        val tenantKey = request.tenantId().key
        val sort = catalogListSort(request)
        val rows = ListCatalogsForManagement(tenantKey).query()
            .let { all -> sort.search?.let { s -> all.filter { it.matchesSearch(s) } } ?: all }
            .sortedWith(catalogComparator(sort.sortKey, sort.direction))
        "tenantId" to tenantKey
        "catalogs" to rows
        "searchValue" to sort.search
        "query" to sort
    }

    /**
     * Parse the catalog list's search + sort state from the request. On a mutation
     * (create/delete/release/upgrade) the params ride [listParam]'s `HX-Current-URL`
     * fallback, so the re-rendered list keeps the view the user was on.
     */
    private fun catalogListSort(request: ServerRequest): CatalogListSort {
        val tenantKey = request.tenantId().key
        val sortKey = request.listParam("sort")?.takeIf { it in CATALOG_SORT_KEYS } ?: DEFAULT_CATALOG_SORT
        val direction = when (request.listParam("dir")?.lowercase()) {
            "desc" -> SortDirection.DESC
            "asc" -> SortDirection.ASC
            else -> SortDirection.ASC
        }
        return CatalogListSort(
            basePath = "/tenants/${tenantKey.value}/catalogs",
            search = request.listParam("q"),
            sortKey = sortKey,
            direction = direction,
        )
    }

    private fun catalogComparator(sortKey: String, direction: SortDirection): Comparator<CatalogListRow> {
        val base: Comparator<CatalogListRow> = when (sortKey) {
            "id" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.catalog.id.value }
            "type" -> compareBy<CatalogListRow> { it.catalog.type.name }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.catalog.name }
            "updated" -> compareBy { it.catalog.updatedAt }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.catalog.name }
        }
        return if (direction == SortDirection.ASC) base else base.reversed()
    }

    private fun listWithError(request: ServerRequest, error: String): ServerResponse = ServerResponse.ok().page("catalogs/list") {
        "pageTitle" to "Catalogs - Epistola"
        "activeNavSection" to "catalogs"
        catalogListModel(request)
        "error" to error
    }
}

/** Logical sort keys the catalogs table allows; anything else falls back to [DEFAULT_CATALOG_SORT]. */
private val CATALOG_SORT_KEYS = setOf("name", "id", "type", "updated")
private const val DEFAULT_CATALOG_SORT = "name"

/** Free-text match for the catalog search box: name or id, case-insensitive. */
private fun CatalogListRow.matchesSearch(term: String): Boolean {
    val needle = term.trim().lowercase()
    if (needle.isEmpty()) return true
    return catalog.name.lowercase().contains(needle) || catalog.id.value.lowercase().contains(needle)
}

/**
 * The sort-link/URL authority for the (unpaginated) catalogs table — mirrors the data-table
 * fragment's `ListQuery` API (`sortUrl` / `isSorted` / `ascending`) so the catalog table's
 * sortable headers use identical markup, but builds clean `?q=&sort=&dir=` URLs with no
 * `size`/`page` (catalogs deliberately do not paginate). The enclosing list `<form>` carries
 * the search term; sort travels in these link URLs. See ADR 0007.
 */
class CatalogListSort(
    val basePath: String,
    val search: String?,
    val sortKey: String,
    val direction: SortDirection,
) {
    fun ascending(): Boolean = direction == SortDirection.ASC

    fun isSorted(columnKey: String): Boolean = columnKey == sortKey

    /** Sort by [columnKey]: ascending normally; flip to descending if it is already asc. */
    fun sortUrl(columnKey: String): String {
        val nextDirection =
            if (columnKey == sortKey && direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        return url(columnKey, nextDirection)
    }

    /** Canonical URL for the current state — the handler pushes this with `HX-Push-Url`. */
    fun canonicalUrl(): String = url(sortKey, direction)

    private fun url(sort: String, dir: SortDirection): String {
        val builder = UriComponentsBuilder.fromPath(basePath)
        if (!search.isNullOrBlank()) builder.queryParam("q", search)
        builder.queryParam("sort", sort)
        builder.queryParam("dir", if (dir == SortDirection.ASC) "asc" else "desc")
        return builder.build().encode().toUriString()
    }
}
