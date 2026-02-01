package app.epistola.suite.themes.commands

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.themes.Theme
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CreateTheme(
    val id: ThemeId,
    val tenantId: TenantId,
    val name: String,
    val description: String? = null,
    val documentStyles: DocumentStyles = DocumentStyles(),
    val pageSettings: PageSettings? = null,
    val blockStylePresets: Map<String, Map<String, Any>>? = null,
) : Command<Theme> {
    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 255) { "Name must be 255 characters or less" }
    }
}

@Component
class CreateThemeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateTheme, Theme> {
    override fun handle(command: CreateTheme): Theme = jdbi.withHandle<Theme, Exception> { handle ->
        handle.createQuery(
            """
            INSERT INTO themes (id, tenant_id, name, description, document_styles, page_settings, block_style_presets, created_at, last_modified)
            VALUES (:id, :tenantId, :name, :description, :documentStyles::jsonb, :pageSettings::jsonb, :blockStylePresets::jsonb, NOW(), NOW())
            RETURNING *
            """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .bind("name", command.name)
            .bind("description", command.description)
            .bind("documentStyles", objectMapper.writeValueAsString(command.documentStyles))
            .bind("pageSettings", command.pageSettings?.let { objectMapper.writeValueAsString(it) })
            .bind("blockStylePresets", command.blockStylePresets?.let { objectMapper.writeValueAsString(it) })
            .mapTo<Theme>()
            .one()
    }
}
