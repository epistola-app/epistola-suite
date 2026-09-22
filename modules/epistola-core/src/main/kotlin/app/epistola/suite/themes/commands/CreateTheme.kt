// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.Theme
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CreateTheme(
    val id: ThemeId,
    val name: String,
    val description: String? = null,
    val documentStyles: DocumentStyles = emptyMap(),
    val pageSettings: PageSettings? = null,
    val blockStylePresets: BlockStylePresets? = null,
    val spacingUnit: Float? = null,
) : Command<Theme>,
    RequiresPermission {
    override val permission = Permission.THEME_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class CreateThemeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateTheme, Theme> {
    override fun handle(command: CreateTheme): Theme {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        val auditUser = currentUserIdOrNull()?.value
        return executeOrThrowDuplicate("theme", command.id.key.value) {
            jdbi.withHandle<Theme, Exception> { handle ->
                handle.createQuery(
                    """
                INSERT INTO themes (id, tenant_key, catalog_key, name, description, document_styles, page_settings, block_style_presets, spacing_unit, created_at, updated_at, created_by, updated_by)
                VALUES (:id, :tenantId, :catalogKey, :name, :description, :documentStyles::jsonb, :pageSettings::jsonb, :blockStylePresets::jsonb, :spacingUnit, NOW(), NOW(), :createdBy, :updatedBy)
                RETURNING *
                """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("name", command.name)
                    .bind("description", command.description)
                    .bind("documentStyles", objectMapper.writeValueAsString(command.documentStyles))
                    .bind("pageSettings", command.pageSettings?.let { objectMapper.writeValueAsString(it) })
                    .bind("blockStylePresets", command.blockStylePresets?.let { objectMapper.writeValueAsString(it) })
                    .bind("spacingUnit", command.spacingUnit)
                    .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                    .mapTo<Theme>()
                    .one()
            }
        }
    }
}
