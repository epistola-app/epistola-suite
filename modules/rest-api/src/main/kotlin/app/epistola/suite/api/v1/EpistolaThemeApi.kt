package app.epistola.suite.api.v1

import app.epistola.api.ThemesApi
import app.epistola.api.model.CreateThemeRequest
import app.epistola.api.model.ThemeDto
import app.epistola.api.model.ThemeListResponse
import app.epistola.api.model.UpdateThemeRequest
import app.epistola.suite.api.v1.shared.toDomain
import app.epistola.suite.api.v1.shared.toDomainDocumentStyles
import app.epistola.suite.api.v1.shared.toDomainPresets
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/api")
class EpistolaThemeApi(
    private val objectMapper: ObjectMapper,
) : ThemesApi {

    override fun listThemes(
        tenantId: String,
        q: String?,
    ): ResponseEntity<ThemeListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val themes = ListThemes(
            tenantId = tenantIdComposite,
            searchTerm = q,
        ).query()

        return ResponseEntity.ok(
            ThemeListResponse(
                items = themes.map { it.toDto(objectMapper) },
            ),
        )
    }

    override fun createTheme(
        tenantId: String,
        createThemeRequest: CreateThemeRequest,
    ): ResponseEntity<ThemeDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val themeIdComposite = ThemeId(ThemeKey.of(createThemeRequest.id), tenantIdComposite)
        val theme = CreateTheme(
            id = themeIdComposite,
            name = createThemeRequest.name,
            description = createThemeRequest.description,
            documentStyles = createThemeRequest.documentStyles.toDomainDocumentStyles(objectMapper),
            pageSettings = createThemeRequest.pageSettings?.toDomain(),
            blockStylePresets = createThemeRequest.blockStylePresets?.toDomainPresets(objectMapper),
            spacingUnit = createThemeRequest.spacingUnit?.toFloat(),
        ).execute()

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(theme.toDto(objectMapper))
    }

    override fun getTheme(
        tenantId: String,
        themeId: String,
    ): ResponseEntity<ThemeDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val themeIdComposite = ThemeId(ThemeKey.of(themeId), tenantIdComposite)
        val theme = GetTheme(
            id = themeIdComposite,
        ).query() ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(theme.toDto(objectMapper))
    }

    override fun updateTheme(
        tenantId: String,
        themeId: String,
        updateThemeRequest: UpdateThemeRequest,
    ): ResponseEntity<ThemeDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val themeIdComposite = ThemeId(ThemeKey.of(themeId), tenantIdComposite)
        val theme = UpdateTheme(
            id = themeIdComposite,
            name = updateThemeRequest.name,
            description = updateThemeRequest.description,
            documentStyles = updateThemeRequest.documentStyles?.toDomainDocumentStyles(objectMapper),
            pageSettings = updateThemeRequest.pageSettings?.toDomain(),
            blockStylePresets = updateThemeRequest.blockStylePresets?.toDomainPresets(objectMapper),
            spacingUnit = updateThemeRequest.spacingUnit?.toFloat(),
        ).execute() ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(theme.toDto(objectMapper))
    }

    override fun deleteTheme(
        tenantId: String,
        themeId: String,
    ): ResponseEntity<Unit> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val themeIdComposite = ThemeId(ThemeKey.of(themeId), tenantIdComposite)
        val deleted = DeleteTheme(
            id = themeIdComposite,
        ).execute()

        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
