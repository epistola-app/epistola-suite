// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CODE_LIST_AT_ADDRESS
import app.epistola.suite.catalog.contentRevision
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.Revisions
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
    /** The revision of the code list the caller read; null: no check, last write wins. */
    val expectedRevision: Long? = null,
) : CatalogVersionScoped,
    Command<Boolean>,
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
            // An entry is part of its code list: the caller names the list's revision, and a change to
            // an entry is a change to the list, so its revision moves on.
            Revisions.refuse(
                "Code list '${command.codeListId.key.value}'",
                command.expectedRevision,
                handle.contentRevision("code_lists", "slug", command.codeListId.tenantKey, command.codeListId.catalogKey, command.codeListId.key),
            )
            fun entries(set: String, where: String = "") = handle.createUpdate(
                """
                UPDATE code_list_entries
                SET $set
                WHERE tenant_key = :tenantKey
                  AND version_id = ${versionAt("tenantKey", "catalogKey")}
                  AND code_list_resource_id = $CODE_LIST_AT_ADDRESS
                  AND code = :code $where
                """,
            )
                .bind("tenantKey", command.codeListId.tenantKey)
                .bind("catalogKey", command.codeListId.catalogKey)
                .bind("slug", command.codeListId.key)
                .bind("code", command.code)
                .bind("hidden", command.hidden)
                .execute()
            val changed = entries("hidden = :hidden", "AND hidden IS DISTINCT FROM :hidden")
            if (changed > 0) {
                // bump_content_revision keeps a raise by one the update makes itself.
                handle.createUpdate("UPDATE code_lists SET revision = revision + 1 WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")} AND slug = :slug")
                    .bind("tenantKey", command.codeListId.tenantKey)
                    .bind("catalogKey", command.codeListId.catalogKey)
                    .bind("slug", command.codeListId.key)
                    .execute()
                true
            } else {
                // Unchanged, or not there: an entry already as asked counts as found.
                entries("hidden = hidden") > 0
            }
        }
    }
}
