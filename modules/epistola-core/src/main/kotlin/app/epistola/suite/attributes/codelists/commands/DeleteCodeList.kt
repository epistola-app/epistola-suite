// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
 * Thrown when a code list cannot be deleted because attributes still bind to it.
 *
 * The DB-level FK from `variant_attribute_definitions.(tenant, catalog, slug)`
 * to `code_lists` uses `ON DELETE RESTRICT`, so this would surface as a JDBC
 * exception otherwise; we catch and translate for a clearer error.
 */
class CodeListInUseException(
    val codeListId: CodeListId,
    val attributeCount: Long,
) : RuntimeException(
    "Cannot delete code list '${codeListId.key.value}' in catalog '${codeListId.catalogKey.value}': " +
        "it is still referenced by $attributeCount attribute definition(s). Unbind the attributes first.",
)

data class DeleteCodeList(
    val id: CodeListId,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey
}

@Component
class DeleteCodeListHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteCodeList, Boolean> {
    override fun handle(command: DeleteCodeList): Boolean {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val attributeCount = handle.createQuery(
                """
                SELECT COUNT(*) FROM variant_attribute_definitions
                WHERE tenant_key = :tenantKey
                  AND code_list_catalog_key = :catalogKey
                  AND code_list_slug = :slug
                """,
            )
                .bind("tenantKey", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("slug", command.id.key)
                .mapTo(Long::class.java)
                .one()

            if (attributeCount > 0) {
                throw CodeListInUseException(command.id, attributeCount)
            }

            // Entries cascade-delete via FK ON DELETE CASCADE.
            val rowsAffected = handle.createUpdate(
                """
                DELETE FROM code_lists
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
                """,
            )
                .bind("tenantKey", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("slug", command.id.key)
                .execute()
            rowsAffected > 0
        }
    }
}
