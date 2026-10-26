// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
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
import app.epistola.suite.themes.ThemeFields
import app.epistola.suite.themes.ThemeStore
import app.epistola.suite.themes.crossCatalogRefs
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
    /** The baseline margins of every flow block (`marginTop`/`marginBottom`); null for the renderer's fallback. */
    val defaultBlockStyles: Map<String, Any?>? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Theme>,
    RequiresPermission {
    override val permission = Permission.THEME_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validate("defaultBlockStyles", defaultBlockStyles.orEmpty().keys.all { it == "marginTop" || it == "marginBottom" }) { "A theme's default block styles set marginTop and marginBottom only" }
    }
}

@Component
class CreateThemeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val usedCatalogs: UsedCatalogDeclarer,
    private val themes: ThemeStore,
) : CommandHandler<CreateTheme, Theme> {
    override fun handle(command: CreateTheme): Theme {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        usedCatalogs.declare(command.id.catalogId, saved.crossCatalogRefs(objectMapper))
        return saved
    }

    private fun save(command: CreateTheme): Theme {
        requireCatalogEditable(command.id.catalogId)
        val auditUser = currentUserIdOrNull()?.value
        return executeOrThrowDuplicate("theme", command.id.key.value) {
            jdbi.withHandle<Theme, Exception> { handle ->
                themes.create(
                    handle,
                    command.id,
                    ThemeFields(command.name, command.description, command.documentStyles, command.pageSettings, command.blockStylePresets, command.spacingUnit, command.defaultBlockStyles),
                    auditUser,
                )
            }
        }
    }
}
