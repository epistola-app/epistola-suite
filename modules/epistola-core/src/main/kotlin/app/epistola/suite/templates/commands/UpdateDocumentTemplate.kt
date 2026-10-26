// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.TemplateStore
import app.epistola.suite.templates.fitsTemplateDescription
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.themes.themeAtAddress
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.FieldLimits.MAX_TEMPLATE_DESCRIPTION_LENGTH
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Updates a template's metadata (name, description, theme, pdfaEnabled).
 * The data contract is updated with [app.epistola.suite.templates.contracts.commands.UpdateDataContract].
 */
data class UpdateDocumentTemplate(
    val id: TemplateId,
    val name: String? = null,
    val themeId: ThemeKey? = null,
    /** The catalog [themeId] is in, by its address (ADR 0034, section 9). */
    val themeCatalog: app.epistola.suite.common.ids.CatalogId? = null,
    val clearThemeId: Boolean = false,
    val pdfaEnabled: Boolean? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The template's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
    /** The new description; null keeps the current one, blank removes it. */
    val description: String? = null,
) : CatalogReleaseScoped,
    Command<DocumentTemplate?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
    val themeCatalogKey: CatalogKey? get() = themeCatalog?.key

    init {
        if (name != null) {
            validate("name", name.isNotBlank()) { "Name cannot be blank" }
            validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        }
        validate("themeCatalogKey", themeId == null || themeCatalog != null) { "A theme needs the catalog it lives in" }
        validate("description", description.fitsTemplateDescription()) { "Description must be $MAX_TEMPLATE_DESCRIPTION_LENGTH characters or less" }
    }
}

@Component
class UpdateDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val usedCatalogs: UsedCatalogDeclarer,
    private val templates: TemplateStore,
) : CommandHandler<UpdateDocumentTemplate, DocumentTemplate?> {
    @Transactional
    override fun handle(command: UpdateDocumentTemplate): DocumentTemplate? {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        if (saved != null && command.themeId != null) usedCatalogs.declare(command.id.catalogId, command.themeCatalog)
        return saved
    }

    private fun save(command: UpdateDocumentTemplate): DocumentTemplate? {
        requireCatalogEditable(command.id.catalogId)

        val changes = command.name != null || command.description != null || command.clearThemeId || command.themeId != null || command.pdfaEnabled != null
        if (!changes) {
            return GetDocumentTemplate(id = command.id).query()?.also { Revisions.refuse("Template '${command.id.key.value}'", command.expectedRevision, it.revision) }
        }

        val saved = jdbi.inTransaction<Boolean, Exception> { handle ->
            if (!command.clearThemeId && command.themeId != null) {
                val themeCatalogKey = command.themeCatalog?.address
                val themeExists = handle.createQuery(
                    """
                    SELECT 1
                    FROM current_themes
                    WHERE tenant_key = :tenantId
                      AND catalog_id = ${catalogAt("tenantId", "themeCatalogKey")}
                      AND id = :themeId
                    """,
                )
                    .bind("tenantId", command.id.tenantKey)
                    .bindCatalog("themeCatalogKey", command.themeCatalog ?: command.id.catalogId)
                    .bind("themeId", command.themeId)
                    .mapTo(Int::class.java)
                    .findOne()
                    .isPresent
                if (!themeExists) {
                    throw ValidationException(
                        "themeId",
                        "The selected theme '$themeCatalogKey/${command.themeId}' no longer exists. Refresh the page and choose another theme.",
                    )
                }
            }
            templates.modify(handle, command.id, currentUserIdOrNull()?.value, command.expectedRevision, "Template '${command.id.key.value}'") { template ->
                var changed = template
                command.name?.let { changed = changed.copy(name = it) }
                command.description?.let { changed = changed.copy(description = it.takeIf(String::isNotBlank)) }
                if (command.clearThemeId) {
                    changed = changed.copy(themeKey = null, themeCatalogKey = null)
                } else if (command.themeId != null) {
                    // By alias (ADR 0034): a theme of the template's own catalog is named without one.
                    val alias = command.themeCatalog?.takeUnless { it == command.id.catalogId }?.key?.value
                    changed = changed.copy(themeKey = command.themeId.value, themeCatalogKey = alias)
                }
                command.pdfaEnabled?.let { changed = changed.copy(pdfaEnabled = it) }
                changed
            } != null
        }

        if (!saved) return null

        return GetDocumentTemplate(id = command.id).query()
    }
}

/** The revision of template [id] in the version the caller means, or null when there is no such template. */
internal fun templateRevision(handle: Handle, id: TemplateId): Long? = handle.createQuery(
    "SELECT revision FROM current_templates WHERE tenant_key = :t AND id = :k AND catalog_id = ${catalogAt("t", "c")}",
)
    .bind("t", id.tenantKey)
    .bindCatalog("c", id.catalogId)
    .bind("k", id.key)
    .mapTo(Long::class.java)
    .findOne()
    .orElse(null)
