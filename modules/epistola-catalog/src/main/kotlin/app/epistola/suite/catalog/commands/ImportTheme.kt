package app.epistola.suite.catalog.commands

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.themes.BlockStylePresets
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class ImportTheme(
    val tenantId: TenantId,
    val slug: String,
    val name: String,
    val description: String? = null,
    val documentStyles: DocumentStyles = emptyMap(),
    val pageSettings: PageSettings? = null,
    val blockStylePresets: BlockStylePresets? = null,
    val spacingUnit: Float? = null,
) : Command<InstallStatus>,
    RequiresPermission {
    override val permission get() = Permission.THEME_EDIT
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ImportThemeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<ImportTheme, InstallStatus> {

    override fun handle(command: ImportTheme): InstallStatus {
        val themeKey = ThemeKey.of(command.slug)
        val documentStylesJson = objectMapper.writeValueAsString(command.documentStyles)
        val pageSettingsJson = command.pageSettings?.let { objectMapper.writeValueAsString(it) }
        val blockStylePresetsJson = command.blockStylePresets?.let { objectMapper.writeValueAsString(it) }

        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) > 0 FROM themes WHERE id = :id AND tenant_key = :tenantKey")
                .bind("id", themeKey)
                .bind("tenantKey", command.tenantKey)
                .mapTo(Boolean::class.java)
                .one()
        }

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO themes (id, tenant_key, name, description, document_styles, page_settings, block_style_presets, spacing_unit, created_at, last_modified)
                VALUES (:id, :tenantKey, :name, :description, :documentStyles::jsonb, :pageSettings::jsonb, :blockStylePresets::jsonb, :spacingUnit, NOW(), NOW())
                ON CONFLICT (tenant_key, id) DO UPDATE
                SET name = :name, description = :description, document_styles = :documentStyles::jsonb,
                    page_settings = :pageSettings::jsonb, block_style_presets = :blockStylePresets::jsonb,
                    spacing_unit = :spacingUnit, last_modified = NOW()
                """,
            )
                .bind("id", themeKey)
                .bind("tenantKey", command.tenantKey)
                .bind("name", command.name)
                .bind("description", command.description)
                .bind("documentStyles", documentStylesJson)
                .bind("pageSettings", pageSettingsJson)
                .bind("blockStylePresets", blockStylePresetsJson)
                .bind("spacingUnit", command.spacingUnit)
                .execute()
        }

        return if (exists) InstallStatus.UPDATED else InstallStatus.INSTALLED
    }
}
