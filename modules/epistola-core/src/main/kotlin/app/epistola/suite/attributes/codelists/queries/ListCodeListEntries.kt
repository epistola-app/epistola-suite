package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists entries belonging to a code list, ordered by `sort_order` then `code`.
 *
 * Hidden entries are filtered out by default (the common case for pickers).
 * Pass `includeHidden = true` for the detail page where the user manages them.
 */
data class ListCodeListEntries(
    val codeListId: CodeListId,
    val includeHidden: Boolean = false,
) : Query<List<CodeListEntry>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = codeListId.tenantKey
}

@Component
class ListCodeListEntriesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCodeListEntries, List<CodeListEntry>> {
    override fun handle(query: ListCodeListEntries): List<CodeListEntry> = jdbi.withHandle<List<CodeListEntry>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT code, label, sort_order, hidden
                FROM code_list_entries
                WHERE tenant_key = :tenantKey
                  AND catalog_key = :catalogKey
                  AND code_list_slug = :slug
                """,
            )
            if (!query.includeHidden) {
                append(" AND NOT hidden")
            }
            append(" ORDER BY sort_order ASC, code ASC")
        }

        handle.createQuery(sql)
            .bind("tenantKey", query.codeListId.tenantKey)
            .bind("catalogKey", query.codeListId.catalogKey)
            .bind("slug", query.codeListId.key)
            .mapTo<CodeListEntry>()
            .list()
    }
}
