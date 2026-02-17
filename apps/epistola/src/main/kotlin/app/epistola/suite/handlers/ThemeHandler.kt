package app.epistola.suite.themes

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
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
    val pageSettings: PageSettings? = null,
    val clearPageSettings: Boolean = false,
    val blockStylePresets: BlockStylePresets? = null,
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
            "layout/shell",
            mapOf(
                "contentView" to "themes/list",
                "pageTitle" to "Themes - Epistola",
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

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "themes/new",
                "pageTitle" to "New Theme - Epistola",
                "tenantId" to tenantId,
            ),
        )
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val slug = request.params().getFirst("slug")?.trim().orEmpty()
        val name = request.params().getFirst("name")?.trim().orEmpty()
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }

        fun renderFormWithErrors(errors: Map<String, String>): ServerResponse {
            val formData = mapOf("slug" to slug, "name" to name, "description" to (description ?: ""))
            return ServerResponse.ok().render(
                "layout/shell",
                mapOf(
                    "contentView" to "themes/new",
                    "pageTitle" to "New Theme - Epistola",
                    "tenantId" to tenantId,
                    "formData" to formData,
                    "errors" to errors,
                ),
            )
        }

        // Validate slug
        val themeId = ThemeId.validateOrNull(slug)
        if (themeId == null) {
            return renderFormWithErrors(
                mapOf("slug" to "Invalid theme ID format. Must be 3-20 characters, start with a letter, and contain only lowercase letters, numbers, and hyphens."),
            )
        }

        try {
            CreateTheme(
                id = themeId,
                tenantId = TenantId.of(tenantId),
                name = name,
                description = description,
            ).execute()
        } catch (e: ValidationException) {
            return renderFormWithErrors(mapOf(e.field to e.message))
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/$tenantId/themes/$slug")
            .build()
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val themeIdStr = request.pathVariable("themeId")
        val themeId = ThemeId.validateOrNull(themeIdStr)
            ?: return ServerResponse.badRequest().build()

        val theme = GetTheme(tenantId = TenantId.of(tenantId), id = themeId).query()
            ?: return ServerResponse.notFound().build()

        // Serialize theme data as JSON for the Lit component
        val themeJson = mapOf(
            "id" to theme.id.value,
            "name" to theme.name,
            "description" to theme.description,
            "documentStyles" to theme.documentStyles,
            "pageSettings" to theme.pageSettings,
            "blockStylePresets" to (theme.blockStylePresets ?: emptyMap()),
        )

        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "themes/detail",
                "pageTitle" to "${theme.name} - Epistola",
                "tenantId" to tenantId,
                "theme" to theme,
                "themeJson" to themeJson,
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
            pageSettings = updateRequest.pageSettings,
            clearPageSettings = updateRequest.clearPageSettings,
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
                    "pageSettings" to theme.pageSettings,
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
