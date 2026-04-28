package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Updates a template's metadata (name, theme, pdfaEnabled).
 * The data contract (schema, dataModel, dataExamples) is now versioned separately
 * in contract_versions and managed via contract version commands.
 */
data class UpdateDocumentTemplate(
    val id: TemplateId,
    val name: String? = null,
    val themeId: ThemeKey? = null,
    val themeCatalogKey: app.epistola.suite.common.ids.CatalogKey? = null,
    val clearThemeId: Boolean = false,
    val pdfaEnabled: Boolean? = null,
) : Command<DocumentTemplate?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class UpdateDocumentTemplateHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateDocumentTemplate, DocumentTemplate?> {
    override fun handle(command: UpdateDocumentTemplate): DocumentTemplate? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)

        // Build dynamic UPDATE query
        val updates = mutableListOf<String>()
        val bindings = mutableMapOf<String, Any?>()

        if (command.name != null) {
            updates.add("name = :name")
            bindings["name"] = command.name
        }
        if (command.clearThemeId) {
            updates.add("theme_key = NULL")
            updates.add("theme_catalog_key = NULL")
        } else if (command.themeId != null) {
            updates.add("theme_key = :themeId")
            updates.add("theme_catalog_key = :themeCatalogKey")
            bindings["themeId"] = command.themeId
            bindings["themeCatalogKey"] = command.themeCatalogKey
        }
        if (command.pdfaEnabled != null) {
            updates.add("pdfa_enabled = :pdfaEnabled")
            bindings["pdfaEnabled"] = command.pdfaEnabled
        }

        if (updates.isEmpty()) {
            return getExisting(command.id)
        }

        updates.add("last_modified = NOW()")

        val sql = """
            UPDATE document_templates
            SET ${updates.joinToString(", ")}
            WHERE id = :id AND tenant_key = :tenantId AND catalog_key = :catalogKey
        """

        val rowsUpdated = jdbi.withHandle<Int, Exception> { handle ->
            val update = handle.createUpdate(sql)
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)

            bindings.forEach { (key, value) -> update.bind(key, value) }

            update.execute()
        }

        if (rowsUpdated == 0) return null

        // Refetch with full context (catalog_type via JOIN)
        return GetDocumentTemplate(id = command.id).query()
    }

    private fun getExisting(id: TemplateId): DocumentTemplate? = GetDocumentTemplate(id = id).query()
}
