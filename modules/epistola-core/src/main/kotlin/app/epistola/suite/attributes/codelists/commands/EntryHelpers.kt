// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.validation.FieldLimits.MAX_CODE_LIST_ENTRY_CODE_LENGTH
import app.epistola.suite.validation.FieldLimits.MAX_CODE_LIST_ENTRY_LABEL_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle

internal fun validateCodeListEntries(entries: List<CodeListEntry>) {
    validate("entries", entries.all { it.code.isNotBlank() }) { "Entry codes must not be blank" }
    validate("entries", entries.all { it.label.isNotBlank() }) { "Entry labels must not be blank" }
    validate("entries", entries.all { it.code.length <= MAX_CODE_LIST_ENTRY_CODE_LENGTH }) { "Entry codes must be $MAX_CODE_LIST_ENTRY_CODE_LENGTH characters or less" }
    validate("entries", entries.all { it.label.length <= MAX_CODE_LIST_ENTRY_LABEL_LENGTH }) { "Entry labels must be $MAX_CODE_LIST_ENTRY_LABEL_LENGTH characters or less" }
    validate("entries", entries.map { it.code }.toSet().size == entries.size) { "Entry codes must be unique within a code list" }
}

/**
 * Bulk insert of entries for a code list. Caller is responsible for transaction
 * scope and for ensuring no rows already exist (callers either insert into a
 * freshly-created code list, or DELETE rows first inside the same transaction).
 */
internal fun insertEntries(handle: Handle, id: CodeListId, entries: List<CodeListEntry>) {
    if (entries.isEmpty()) return
    validateCodeListEntries(entries)
    val batch = handle.prepareBatch(
        """
        INSERT INTO code_list_entries (tenant_key, catalog_key, code_list_slug, code, label, sort_order, hidden)
        VALUES (:tenantKey, :catalogKey, :slug, :code, :label, :sortOrder, :hidden)
        """,
    )
    for (entry in entries) {
        batch.bind("tenantKey", id.tenantKey)
            .bind("catalogKey", id.catalogKey)
            .bind("slug", id.key)
            .bind("code", entry.code)
            .bind("label", entry.label)
            .bind("sortOrder", entry.sortOrder)
            .bind("hidden", entry.hidden)
            .add()
    }
    batch.execute()
}
