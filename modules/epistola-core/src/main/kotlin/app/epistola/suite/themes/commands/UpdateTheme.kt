package app.epistola.suite.themes.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.Theme
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Updates a theme with partial updates (only provided fields are updated).
 */
data class UpdateTheme(
    val id: ThemeId,
    val name: String? = null,
    val description: String? = null,
    val clearDescription: Boolean = false,
    val documentStyles: DocumentStyles? = null,
    val pageSettings: PageSettings? = null,
    val clearPageSettings: Boolean = false,
    val blockStylePresets: BlockStylePresets? = null,
    val clearBlockStylePresets: Boolean = false,
    val spacingUnit: Float? = null,
    val clearSpacingUnit: Boolean = false,
) : Command<Theme?>,
    RequiresPermission {
    override val permission = Permission.THEME_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        if (name != null) {
            validate("name", name.isNotBlank()) { "Name cannot be blank" }
            validate("name", name.length <= 255) { "Name must be 255 characters or less" }
        }
    }
}

@Component
class UpdateThemeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateTheme, Theme?> {
    override fun handle(command: UpdateTheme): Theme? {
        val updates = mutableListOf<String>()
        val bindings = mutableMapOf<String, Any?>()

        if (command.name != null) {
            updates.add("name = :name")
            bindings["name"] = command.name
        }

        if (command.clearDescription) {
            updates.add("description = NULL")
        } else if (command.description != null) {
            updates.add("description = :description")
            bindings["description"] = command.description
        }

        if (command.documentStyles != null) {
            updates.add("document_styles = :documentStyles::jsonb")
            bindings["documentStyles"] = objectMapper.writeValueAsString(command.documentStyles)
        }

        if (command.clearPageSettings) {
            updates.add("page_settings = NULL")
        } else if (command.pageSettings != null) {
            updates.add("page_settings = :pageSettings::jsonb")
            bindings["pageSettings"] = objectMapper.writeValueAsString(command.pageSettings)
        }

        if (command.clearBlockStylePresets) {
            updates.add("block_style_presets = NULL")
        } else if (command.blockStylePresets != null) {
            updates.add("block_style_presets = :blockStylePresets::jsonb")
            bindings["blockStylePresets"] = objectMapper.writeValueAsString(command.blockStylePresets)
        }

        if (command.clearSpacingUnit) {
            updates.add("spacing_unit = NULL")
        } else if (command.spacingUnit != null) {
            updates.add("spacing_unit = :spacingUnit")
            bindings["spacingUnit"] = command.spacingUnit
        }

        if (updates.isEmpty()) {
            // No updates to apply, return existing theme
            return getExisting(command.id)
        }

        updates.add("last_modified = NOW()")

        val sql = """
            UPDATE themes
            SET ${updates.joinToString(", ")}
            WHERE id = :id AND tenant_key = :tenantId
            RETURNING *
        """

        return jdbi.withHandle<Theme?, Exception> { handle ->
            val query = handle.createQuery(sql)
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)

            bindings.forEach { (key, value) -> query.bind(key, value) }

            query.mapTo<Theme>()
                .findOne()
                .orElse(null)
        }
    }

    private fun getExisting(id: ThemeId): Theme? = jdbi.withHandle<Theme?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT * FROM themes WHERE id = :id AND tenant_key = :tenantId
            """,
        )
            .bind("id", id.key)
            .bind("tenantId", id.tenantKey)
            .mapTo<Theme>()
            .findOne()
            .orElse(null)
    }
}
