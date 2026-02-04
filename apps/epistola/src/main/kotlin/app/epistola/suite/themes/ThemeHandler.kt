package app.epistola.suite.themes

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import app.epistola.suite.validation.ValidationException
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

/**
 * Request body for updating a theme via PATCH.
 */
data class UpdateThemeRequest(
    val name: String? = null,
    val description: String? = null,
    val clearDescription: Boolean = false,
    val documentStyles: DocumentStyles? = null,
    val blockStylePresets: Map<String, Map<String, Any>>? = null,
    val clearBlockStylePresets: Boolean = false,
)

@Component
class ThemeHandler(
    private val objectMapper: ObjectMapper,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val tenant = GetTenant(id = TenantId.of(tenantId)).query()
        val themes = ListThemes(tenantId = TenantId.of(tenantId)).query()
        return ServerResponse.ok().render(
            "themes/list",
            mapOf(
                "tenantId" to tenantId,
                "tenant" to tenant,
                "themes" to themes,
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val searchTerm = request.param("q").orElse(null)
        val tenant = GetTenant(id = TenantId.of(tenantId)).query()
        val themes = ListThemes(tenantId = TenantId.of(tenantId), searchTerm = searchTerm).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/$tenantId/themes") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val slug = request.params().getFirst("slug")?.trim().orEmpty()
        val name = request.params().getFirst("name")?.trim().orEmpty()
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }

        // Validate slug
        val themeId = ThemeId.validateOrNull(slug)
        if (themeId == null) {
            val formData = mapOf("slug" to slug, "name" to name, "description" to (description ?: ""))
            val errors = mapOf("slug" to "Invalid theme ID format. Must be 3-20 characters, start with a letter, and contain only lowercase letters, numbers, and hyphens.")
            return request.htmx {
                fragment("themes/list", "create-form") {
                    "tenantId" to tenantId
                    "formData" to formData
                    "errors" to errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/tenants/$tenantId/themes") }
            }
        }

        val command = try {
            CreateTheme(
                id = themeId,
                tenantId = TenantId.of(tenantId),
                name = name,
                description = description,
            )
        } catch (e: ValidationException) {
            val formData = mapOf("slug" to slug, "name" to name, "description" to (description ?: ""))
            val errors = mapOf(e.field to e.message)
            return request.htmx {
                fragment("themes/list", "create-form") {
                    "tenantId" to tenantId
                    "formData" to formData
                    "errors" to errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/tenants/$tenantId/themes") }
            }
        }

        command.execute()

        val tenant = GetTenant(id = TenantId.of(tenantId)).query()
        val themes = ListThemes(tenantId = TenantId.of(tenantId)).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId
                "tenant" to tenant
                "themes" to themes
            }
            trigger("themeCreated")
            onNonHtmx { redirect("/tenants/$tenantId/themes") }
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val themeIdStr = request.pathVariable("themeId")
        val themeId = ThemeId.validateOrNull(themeIdStr)
            ?: return ServerResponse.badRequest().build()

        val theme = GetTheme(tenantId = TenantId.of(tenantId), id = themeId).query()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok().render(
            "themes/detail",
            mapOf(
                "tenantId" to tenantId,
                "theme" to theme,
            ),
        )
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val themeIdStr = request.pathVariable("themeId")
        val themeId = ThemeId.validateOrNull(themeIdStr)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateThemeRequest::class.java)

        val theme = UpdateTheme(
            tenantId = TenantId.of(tenantId),
            id = themeId,
            name = updateRequest.name,
            description = updateRequest.description,
            clearDescription = updateRequest.clearDescription,
            documentStyles = updateRequest.documentStyles,
            blockStylePresets = updateRequest.blockStylePresets,
            clearBlockStylePresets = updateRequest.clearBlockStylePresets,
        ).execute()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to theme.id.value,
                    "name" to theme.name,
                    "description" to theme.description,
                    "documentStyles" to theme.documentStyles,
                    "blockStylePresets" to theme.blockStylePresets,
                    "lastModified" to theme.lastModified,
                ),
            )
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val themeIdStr = request.pathVariable("themeId")
        val themeId = ThemeId.validateOrNull(themeIdStr)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteTheme(tenantId = TenantId.of(tenantId), id = themeId).execute()
        } catch (e: ThemeInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        } catch (e: LastThemeException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // Return updated rows for HTMX
        val tenant = GetTenant(id = TenantId.of(tenantId)).query()
        val themes = ListThemes(tenantId = TenantId.of(tenantId)).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/$tenantId/themes") }
        }
    }

    /**
     * Sets the default theme for a tenant.
     */
    fun setDefault(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val themeIdStr = request.params().getFirst("themeId")
            ?: return ServerResponse.badRequest().build()
        val themeId = ThemeId.validateOrNull(themeIdStr)
            ?: return ServerResponse.badRequest().build()

        try {
            SetTenantDefaultTheme(
                tenantId = TenantId.of(tenantId),
                themeId = themeId,
            ).execute()
        } catch (e: ThemeNotFoundException) {
            return ServerResponse.notFound().build()
        }

        // Return updated rows for HTMX
        val tenant = GetTenant(id = TenantId.of(tenantId)).query()
        val themes = ListThemes(tenantId = TenantId.of(tenantId)).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/$tenantId/themes") }
        }
    }
}
