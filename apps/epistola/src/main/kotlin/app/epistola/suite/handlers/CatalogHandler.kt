package app.epistola.suite.handlers

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.UnregisterCatalog
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.htmx.form
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
        val error = request.param("error").orElse(null)

        return ServerResponse.ok().page("catalogs/list") {
            "pageTitle" to "Catalogs - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "catalogs"
            "catalogs" to catalogs
            if (saved) "saved" to true
            if (error != null) "error" to error
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
            return ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs?error=URL is required")
                .build()
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
            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs?error=${e.message}")
                .build()
        }
    }

    fun unregister(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        UnregisterCatalog(tenantKey = tenantId.key, catalogKey = catalogKey).execute()

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/catalogs?saved=true")
            .build()
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
            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs?error=${e.message}")
                .build()
        }
    }

    fun install(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))

        val slugParam = request.param("slug").orElse(null)
        val resourceSlugs = slugParam?.let { listOf(it) }

        return try {
            InstallFromCatalog(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
                resourceSlugs = resourceSlugs,
            ).execute()

            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse?installed=true")
                .build()
        } catch (e: Exception) {
            logger.warn("Failed to install from catalog: ${e.message}", e)
            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse?error=${e.message}")
                .build()
        }
    }
}
