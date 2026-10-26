// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.ThemeFields
import app.epistola.suite.themes.ThemeStore
import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class ImportTheme(
    val catalog: CatalogId,
    val slug: String,
    val name: String,
    val description: String? = null,
    val documentStyles: DocumentStyles = emptyMap(),
    val pageSettings: PageSettings? = null,
    val blockStylePresets: BlockStylePresets? = null,
    val spacingUnit: Float? = null,
    /** The theme's baseline block margins (contract `defaultBlockStyles`). */
    val defaultBlockStyles: Map<String, Any?>? = null,
    /** Producer metadata (contract `CatalogResource.metadata`), as received; null keeps what the draft holds. */
    val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
) : Command<InstallStatus>,
    RequiresPermission {
    val tenantId: TenantId get() = catalog.tenantId
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.THEME_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        // Column ceiling (#692), not the tighter interactive MAX_NAME_LENGTH: imported
        // content that fits themes.name VARCHAR(255) must keep importing.
        validate("name", name.length <= MAX_NAME_COLUMN_LENGTH) { "Name must be $MAX_NAME_COLUMN_LENGTH characters or less" }
    }
}

@Component
class ImportThemeHandler(
    private val jdbi: Jdbi,
    private val themes: ThemeStore,
) : CommandHandler<ImportTheme, InstallStatus> {

    override fun handle(command: ImportTheme): InstallStatus {
        val themeKey = ThemeKey.of(command.slug)
        val auditUser = currentUserIdOrNull()?.value

        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) > 0 FROM current_themes WHERE id = :id AND tenant_key = :tenantKey")
                .bind("id", themeKey.value)
                .bind("tenantKey", command.tenantKey)
                .mapTo(Boolean::class.java)
                .one()
        }

        jdbi.useHandle<Exception> { handle ->
            themes.put(
                handle,
                ThemeId(themeKey, command.catalog),
                ThemeFields(command.name, command.description, command.documentStyles, command.pageSettings, command.blockStylePresets, command.spacingUnit, command.defaultBlockStyles, command.metadata),
                auditUser,
            )
        }

        return if (exists) InstallStatus.UPDATED else InstallStatus.INSTALLED
    }
}
