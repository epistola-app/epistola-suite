package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.common.ids.CodeListId
import org.jdbi.v3.core.Handle

/**
 * Bulk insert of entries for a code list. Caller is responsible for transaction
 * scope and for ensuring no rows already exist (callers either insert into a
 * freshly-created code list, or DELETE rows first inside the same transaction).
 */
internal fun insertEntries(handle: Handle, id: CodeListId, entries: List<CodeListEntry>) {
    if (entries.isEmpty()) return
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
