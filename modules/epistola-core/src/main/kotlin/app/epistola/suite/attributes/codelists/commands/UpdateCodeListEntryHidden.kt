// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CODE_LIST_AT_ADDRESS
import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.contentRevision
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
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
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The revision of the code list the caller read; null: no check, last write wins. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = codeListId.tenantKey
}

@Component
class UpdateCodeListEntryHiddenHandler(
    private val jdbi: Jdbi,
    private val codeLists: CodeListStore,
) : CommandHandler<UpdateCodeListEntryHidden, Boolean> {
    override fun handle(command: UpdateCodeListEntryHidden): Boolean {
        requireCatalogEditable(command.codeListId.catalogId)
        return jdbi.withHandle<Boolean, Exception> { handle ->
            // An entry is part of its code list: the caller names the list's revision, and a change to
            // an entry is a change to the list, so its revision moves on (a new content).
            val revision = DraftResources.lockRevision(handle, command.codeListId.catalogId, CodeListStore.CODE_LIST, command.codeListId.key.value)
            Revisions.refuse("Code list '${command.codeListId.key.value}'", command.expectedRevision, revision)
            revision ?: return@withHandle false
            val current = checkNotNull(codeLists.fields(handle, command.codeListId)) { "Code list ${command.codeListId.key.value} is locked, so it is there" }
            val entry = current.entries.firstOrNull { it.code == command.code } ?: return@withHandle false
            // An entry already as asked counts as found, and writes nothing.
            if (entry.hidden == command.hidden) return@withHandle true
            val entries = current.entries.map { if (it.code == command.code) it.copy(hidden = command.hidden) else it }
            codeLists.write(handle, command.codeListId, current.copy(entries = entries), currentUserIdOrNull()?.value)
        }
    }
}
