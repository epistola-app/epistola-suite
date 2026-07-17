package app.epistola.suite.handlers

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogMigrationConfirmationRequiredException
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
import app.epistola.suite.catalog.queries.CatalogSchemaSyncState
import app.epistola.suite.catalog.queries.CheckCatalogUpgrade
import app.epistola.suite.catalog.queries.FindResourceUsages
import app.epistola.suite.catalog.queries.FindStencilVersionExportConflicts
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogReleaseStatus
import app.epistola.suite.catalog.queries.ListCatalogsForManagement
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.queries.PreviewInstall
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class CatalogHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val saved = request.param("saved").isPresent

        return ServerResponse.ok().page("catalogs/list") {
            "pageTitle" to "Catalogs - Epistola"
            "activeNavSection" to "catalogs"
            catalogListModel(request)
            if (saved) "saved" to true
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("catalogs/new", "dialog") {
                "tenantId" to tenantId.key
            }
            // Direct navigation / boost: the host list page with the create
            // dialog embedded in its mount (openDialog=true), opened on load by JS.
            onNonHtmx {
                page("catalogs/list") {
                    catalogPageModel(request)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/new :: dialog"
                }
            }
        }
    }

    fun createCatalog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)

        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
                asCatalogId()
            }
            field("name") {
                required()
            }
        }

        // Field validation (incl. slug/CatalogKey) and the command-level failure
        // (duplicate slug) both land as `errors` on the FormData, so they share one
        // error path — mirroring the environment/attribute conversions.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                CreateCatalog(
                    tenantKey = tenantId.key,
                    id = CatalogKey.of(form["slug"]),
                    name = form["name"],
                ).execute()
            }
        }

        if (result.hasErrors()) {
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form, not
                // the list) with inline errors + preserved values.
                dialogFieldErrors(
                    template = "catalogs/new",
                    fragmentName = "catalog-form",
                    formTarget = "#create-catalog-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                }
                onNonHtmx {
                    page(422, "catalogs/list") {
                        catalogPageModel(request)
                        "openDialog" to true
                        "openDialogFragment" to "catalogs/new :: dialog"
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: close the dialog + OOB-refresh the list (the catalog-list
        // fragment toggles hx-swap-oob via the `oob` flag dialogSuccess injects).
        return request.htmx {
            dialogSuccess("catalogs/list", "catalog-list") {
                catalogListModel(request)
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/catalogs?saved=true") }
        }
    }

    fun registerForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("catalogs/subscribe", "dialog") {
                "tenantId" to tenantId.key
            }
            // Direct navigation / boost: the host list page with the subscribe
            // dialog embedded in its mount (openDialog=true), opened on load by JS.
            onNonHtmx {
                page("catalogs/list") {
                    catalogPageModel(request)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/subscribe :: dialog"
                }
            }
        }
    }

    fun register(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)

        val form = request.form {
            field("sourceUrl") {
                required()
                maxLength(2000)
            }
        }

        if (form.hasErrors()) {
            return registerError(request, "Catalog URL is required.")
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

            // Stay-on-list: close the dialog + OOB-refresh the list with the new catalog.
            request.htmx {
                dialogSuccess("catalogs/list", "catalog-list") {
                    catalogListModel(request)
                }
                onNonHtmx { redirect("/tenants/${tenantId.key}/catalogs?saved=true") }
            }
        } catch (e: Exception) {
            logger.warn("Failed to register catalog: ${e.message}", e)
            registerError(request, "Failed to register catalog. Check that the URL points to a valid catalog manifest.")
        }
    }

    /**
     * Register error → OOB-update just the dialog's form-error slot, leaving the
     * live form (URL, auth type, credential) exactly as the user left it.
     * dialogFieldErrors would re-render the body and reset the authType <select>
     * / credential toggle, so we keep the operational failure text-only here.
     */
    private fun registerError(request: ServerRequest, message: String): ServerResponse = request.htmx {
        dialogFormError("subscribe-catalog-error", message)
        onNonHtmx {
            page(422, "catalogs/list") {
                catalogPageModel(request)
                "error" to message
            }
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
                model["installedVersion"] = a.installedVersion
                model["availableVersion"] = a.availableVersion
                model["sourceSchemaVersion"] = a.sourceSchemaVersion
                model["currentSchemaVersion"] = a.currentSchemaVersion
                // A schema mismatch (source must republish, or this Epistola is
                // behind) takes priority over the release-version upgrade state.
                model["state"] = when (a.schemaSyncState) {
                    CatalogSchemaSyncState.SOURCE_BEHIND -> "NOT_IN_SYNC"
                    CatalogSchemaSyncState.SOURCE_AHEAD -> "SOURCE_AHEAD"
                    CatalogSchemaSyncState.IN_SYNC -> if (a.available) "UPDATE_AVAILABLE" else "UP_TO_DATE"
                }
            } catch (e: CatalogSchemaTooNewException) {
                // The source publishes a wire schema newer than this instance can
                // read — `fetchMigratedManifest` throws before a sync state can be
                // derived, so surface the intended "upgrade Epistola" state here.
                model["sourceSchemaVersion"] = e.version
                model["currentSchemaVersion"] = e.current
                model["state"] = "SOURCE_AHEAD"
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
            // Per-stencil version-conflict map (slug → "v1, v2 still pinned by N
            // template(s) (latest v3)"). Empty when the catalog is exportable. Used by
            // the browse view to flag stencils that block export — mirrors the precheck
            // the export endpoint runs. The check also fires when templates pin a single
            // stale version (not just multi-version usage), hence the explicit `latest`
            // callout.
            val stencilVersionConflicts = FindStencilVersionExportConflicts(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
            ).query().associate { c ->
                val staleVersions = c.pins.map { it.pinnedVersion }.distinct().sorted()
                    .joinToString(", v", prefix = "v")
                c.stencilKey.value to
                    "$staleVersions still pinned by ${c.pins.size} template(s) (latest v${c.latestPublishedVersion})"
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

    /**
     * The Import-ZIP dialog, mirroring [newForm]/[registerForm]: an in-app
     * trigger (hx-get → #dialog-mount) gets just the `dialog` fragment; direct
     * navigation / boost gets the host list page with the dialog embedded in its
     * mount (openDialog=true), opened on load by JS. Gated on TEMPLATE_EDIT —
     * the same permission [ImportCatalogZip] enforces.
     */
    fun importForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        return request.htmx {
            fragment("catalogs/import", "dialog") { "tenantId" to tenantId.key }
            onNonHtmx {
                page("catalogs/list") {
                    catalogPageModel(request)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/import :: dialog"
                }
            }
        }
    }

    fun importZip(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)

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

        // Set when the operator confirmed updating an AUTHORED ZIP that is below the
        // current catalog schema version but migratable (re-submitted from the
        // "update?" prompt).
        val confirmMigration = multipartData["confirmMigration"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim().equals("true", ignoreCase = true)
        } ?: false

        return try {
            val result = ImportCatalogZip(
                tenantKey = tenantId.key,
                zipBytes = zipBytes,
                catalogType = catalogType,
                authoredMode = authoredMode,
                onStencilConflict = onStencilConflict,
                confirmMigration = confirmMigration,
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
                "catalogs/import :: import-conflict-content",
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
                "catalogs/import :: import-schema-error",
                mapOf(
                    "schemaErrorTitle" to title,
                    "schemaErrorDetail" to (e.message ?: "Incompatible catalog wire format."),
                ),
            )
        } catch (e: CatalogMigrationConfirmationRequiredException) {
            // AUTHORED ZIP below the current catalog schema version but migratable:
            // updating mutates the imported content, so prompt before importing. The
            // "Update" button re-submits the same form with confirmMigration=true.
            ServerResponse.ok().render(
                "catalogs/import :: import-migration-confirm",
                mapOf(
                    "tenantId" to tenantId.key,
                    "fromSchemaVersion" to e.fromVersion,
                    "toSchemaVersion" to e.toVersion,
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

    /**
     * Plain single-message import failures go through the global form-error
     * slot (shaped 422; see HtmxDsl.globalFormError). The structured import
     * responses (conflict report, schema error, migration confirm) keep
     * rendering into the form's dedicated #import-error target instead — the
     * text-only slot cannot hold them. Because the shaped response's primary
     * swap is `none`, stale structured content in #import-error is OOB-reset
     * in the same response.
     */
    private fun importError(request: ServerRequest, error: String): ServerResponse = request.htmx {
        globalFormError("import-catalog-error", error)
        oob("catalogs/import", "import-error-reset")
        onNonHtmx {
            page(422, "catalogs/list") {
                "pageTitle" to "Catalogs - Epistola"
                "activeNavSection" to "catalogs"
                catalogListModel(request)
                "error" to error
            }
        }
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
                    latestPublishedVersion = "v${s.latestPublishedVersion}",
                    pins = s.pins.map { p ->
                        StencilConflictView.PinView(
                            templateLabel = p.displayLabel,
                            pinned = "v${p.pinnedVersion}",
                        )
                    },
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
        val latestPublishedVersion: String,
        /** The published template-variants that still pin an outdated version. */
        val pins: List<PinView>,
    ) {
        data class PinView(
            val templateLabel: String,
            val pinned: String,
        )
    }

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
        "tenantId" to tenantKey
        "catalogs" to ListCatalogsForManagement(tenantKey).query()
    }

    /**
     * The full-page list model used by the newForm / createCatalog non-HTMX
     * branches so the list renders behind the embedded create dialog. Adds the
     * page chrome the host list page needs on top of [catalogListModel].
     */
    private fun ModelBuilder.catalogPageModel(request: ServerRequest) {
        "pageTitle" to "Catalogs - Epistola"
        "activeNavSection" to "catalogs"
        catalogListModel(request)
    }

    private fun listWithError(request: ServerRequest, error: String): ServerResponse = ServerResponse.ok().page("catalogs/list") {
        "pageTitle" to "Catalogs - Epistola"
        "activeNavSection" to "catalogs"
        catalogListModel(request)
        "error" to error
    }
}
