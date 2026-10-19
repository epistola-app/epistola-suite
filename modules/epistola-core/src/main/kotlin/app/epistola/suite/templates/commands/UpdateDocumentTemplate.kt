// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
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
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.themes.themeAtAddress
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Updates a template's metadata (name, theme, pdfaEnabled).
 * The data contract is updated with [app.epistola.suite.templates.contracts.commands.UpdateDataContract].
 */
data class UpdateDocumentTemplate(
    val id: TemplateId,
    val name: String? = null,
    val themeId: ThemeKey? = null,
    val themeCatalogKey: app.epistola.suite.common.ids.CatalogKey? = null,
    val clearThemeId: Boolean = false,
    val pdfaEnabled: Boolean? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
    /** The template's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogVersionScoped,
    Command<DocumentTemplate?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        if (name != null) {
            validate("name", name.isNotBlank()) { "Name cannot be blank" }
            validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        }
    }
}

@Component
class UpdateDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<UpdateDocumentTemplate, DocumentTemplate?> {
    @Transactional
    override fun handle(command: UpdateDocumentTemplate): DocumentTemplate? {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        if (saved != null && command.themeId != null) usedCatalogs.declare(command.id.tenantKey, command.id.catalogKey, command.themeCatalogKey)
        return saved
    }

    private fun save(command: UpdateDocumentTemplate): DocumentTemplate? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)

        // Build dynamic UPDATE query
        val updates = mutableListOf<String>()
        val bindings = mutableMapOf<String, Any?>()

        if (command.name != null) {
            updates.add("name = :name")
            bindings["name"] = command.name
        }
        if (command.clearThemeId) {
            updates.add("theme_resource_id = NULL")
        } else if (command.themeId != null) {
            updates.add("theme_resource_id = ${themeAtAddress("tenantId", "themeCatalogKey", "themeId")}")
            bindings["themeId"] = command.themeId
            bindings["themeCatalogKey"] = command.themeCatalogKey ?: CatalogKey.DEFAULT
        }
        if (command.pdfaEnabled != null) {
            updates.add("pdfa_enabled = :pdfaEnabled")
            bindings["pdfaEnabled"] = command.pdfaEnabled
        }

        if (updates.isEmpty()) {
            return getExisting(command.id)?.also { Revisions.refuse("Template '${command.id.key.value}'", command.expectedRevision, it.revision) }
        }

        updates.add("updated_at = NOW()")
        updates.add("updated_by = :updatedBy")
        bindings["updatedBy"] = currentUserIdOrNull()?.value

        val sql = """
            UPDATE document_templates
            SET ${updates.joinToString(", ")}
            WHERE id = :id AND tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")}
              AND ${Revisions.MATCHES}
        """

        val rowsUpdated = jdbi.withHandle<Int, Exception> { handle ->
            if (!command.clearThemeId && command.themeId != null) {
                val themeCatalogKey = command.themeCatalogKey ?: CatalogKey.DEFAULT
                val themeExists = handle.createQuery(
                    """
                    SELECT 1
                    FROM themes
                    WHERE tenant_key = :tenantId
                      AND version_id = ${versionAt("tenantId", "themeCatalogKey")}
                      AND id = :themeId
                    FOR KEY SHARE
                    """,
                )
                    .bind("tenantId", command.id.tenantKey)
                    .bind("themeCatalogKey", themeCatalogKey)
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

            val update = handle.createUpdate(sql)
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("expectedRevision", command.expectedRevision)

            bindings.forEach { (key, value) -> update.bind(key, value) }

            update.execute().also { updated ->
                if (updated == 0) Revisions.refuse("Template '${command.id.key.value}'", command.expectedRevision, templateRevision(handle, command.id))
            }
        }

        if (rowsUpdated == 0) return null

        // Refetch with full context (catalog_type via JOIN)
        return GetDocumentTemplate(id = command.id).query()
    }

    private fun getExisting(id: TemplateId): DocumentTemplate? = GetDocumentTemplate(id = id).query()
}

/** The revision of template [id] in the version the caller means, or null when there is no such template. */
internal fun templateRevision(handle: Handle, id: TemplateId): Long? = handle.createQuery(
    "SELECT revision FROM document_templates WHERE tenant_key = :t AND id = :k AND version_id = ${versionAt("t", "c")}",
)
    .bind("t", id.tenantKey)
    .bind("c", id.catalogKey)
    .bind("k", id.key)
    .mapTo(Long::class.java)
    .findOne()
    .orElse(null)
