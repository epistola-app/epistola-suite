package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Toggles the `hidden` flag on a single code list entry.
 *
 * Hidden entries are filtered from pickers but remain valid for variant
 * validation, supporting deprecation and tenant-curated subsets without
 * breaking variants that already use those codes.
 */
data class UpdateCodeListEntryHidden(
    val codeListId: CodeListId,
    val code: String,
    val hidden: Boolean,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = codeListId.tenantKey
}

@Component
class UpdateCodeListEntryHiddenHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateCodeListEntryHidden, Boolean> {
    override fun handle(command: UpdateCodeListEntryHidden): Boolean {
        requireCatalogEditable(command.codeListId.tenantKey, command.codeListId.catalogKey)
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val rowsAffected = handle.createUpdate(
                """
                UPDATE code_list_entries
                SET hidden = :hidden
                WHERE tenant_key = :tenantKey
                  AND catalog_key = :catalogKey
                  AND code_list_slug = :slug
                  AND code = :code
                """,
            )
                .bind("tenantKey", command.codeListId.tenantKey)
                .bind("catalogKey", command.codeListId.catalogKey)
                .bind("slug", command.codeListId.key)
                .bind("code", command.code)
                .bind("hidden", command.hidden)
                .execute()
            rowsAffected > 0
        }
    }
}
