package app.epistola.suite.handlers

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.UnregisterCatalog
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
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
                fragment("catalogs/list", "catalog-rows") {
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
            UnregisterCatalog(tenantKey = tenantId.key, catalogKey = catalogKey).execute()

            return request.htmx {
                val catalogs = ListCatalogs(tenantId.key).query()
                fragment("catalogs/list", "catalog-rows") {
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
            listWithError(request, e.message ?: "Catalog is in use by other catalogs")
        } catch (e: Exception) {
            logger.warn("Failed to unregister catalog: ${e.message}", e)
            listWithError(request, e.message ?: "Failed to remove catalog.")
        }
    }

    fun browse(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        return try {
            val result = BrowseCatalog(tenantKey = tenantId.key, catalogKey = catalogKey).query()

            ServerResponse.ok().page("catalogs/browse") {
                "pageTitle" to "${result.catalog.name} - Catalog - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "catalogs"
                "catalog" to result.catalog
                "resources" to result.resources
            }
        } catch (e: Exception) {
            logger.warn("Failed to browse catalog: ${e.message}", e)
            listWithError(request, "Failed to fetch catalog. The remote server may be unavailable or the URL may be incorrect.")
        }
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
            ?: return listWithError(request, "No file provided")

        val zipBytes = filePart.inputStream.use { it.readAllBytes() }

        val catalogTypeStr = multipartData["catalogType"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim()
        } ?: "AUTHORED"
        val catalogType = try {
            CatalogType.valueOf(catalogTypeStr)
        } catch (_: Exception) {
            return listWithError(request, "Invalid catalog type: $catalogTypeStr")
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
            listWithError(request, e.message ?: "Failed to import catalog")
        }
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
