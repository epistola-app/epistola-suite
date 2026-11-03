// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.BoundCodeListVersion
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ContentVersion
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
import app.epistola.suite.common.ResultPage
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
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
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
    /**
     * The catalog of the attribute bound to the list, when one is: its declarations, in the version
     * this addresses of it, say which version of the list's catalog it means (BoundCodeListVersion).
     */
    val boundFrom: CatalogId? = null,
) : ReleaseReadable,
    Query<List<CodeListEntry>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = codeListId.tenantKey
}

/** [ListCodeListEntries], one page of it, cut in the database. */
data class ListCodeListEntryPage(
    val codeListId: CodeListId,
    val includeHidden: Boolean = false,
    val page: PageRequest,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<ResultPage<CodeListEntry>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = codeListId.tenantKey
}

@Component
class ListCodeListEntriesHandler(
    private val jdbi: Jdbi,
    private val boundVersion: BoundCodeListVersion,
) : QueryHandler<ListCodeListEntries, List<CodeListEntry>> {
    override fun handle(query: ListCodeListEntries): List<CodeListEntry> = jdbi.withHandle<List<CodeListEntry>, Exception> { handle ->
        val at = query.boundFrom?.let { boundVersion.of(handle, handle.versionOf(it, query), query.codeListId.catalogId) }
            ?: handle.versionOf(query.codeListId.catalogId, query)
        CodeListEntryList.query(handle, at, query.codeListId, query.includeHidden, paged = false).mapTo<CodeListEntry>().list()
    }
}

@Component
class ListCodeListEntryPageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCodeListEntryPage, ResultPage<CodeListEntry>> {
    override fun handle(query: ListCodeListEntryPage): ResultPage<CodeListEntry> = jdbi.withHandle<ResultPage<CodeListEntry>, Exception> { handle ->
        val at = handle.versionOf(query.codeListId.catalogId, query)
        Paging.read(query.page, CodeListEntry::class.java) { CodeListEntryList.query(handle, at, query.codeListId, query.includeHidden, paged = true) }
    }
}

/** A code list's entries in version [at], by `sort_order`, then `code`; [paged] selects one page of them. */
private object CodeListEntryList {
    fun query(handle: Handle, at: ContentVersion, codeListId: CodeListId, includeHidden: Boolean, paged: Boolean): org.jdbi.v3.core.statement.Query {
        // The entries of the content, in SQL: filtered and ordered there, never in memory (ADR 0033).
        val sql = buildString {
            append(
                """
                SELECT e ->> 'code' AS code, e ->> 'label' AS label,
                       CAST(e ->> 'sortOrder' AS INTEGER) AS sort_order, CAST(e ->> 'hidden' AS BOOLEAN) AS hidden
                """,
            )
            if (paged) append(", ${Paging.TOTAL}")
            append(
                """
                FROM version_code_lists t
                CROSS JOIN LATERAL jsonb_array_elements(t.entries) e
                WHERE t.tenant_key = :t AND t.version_id = :v AND t.id = :key
                """,
            )
            if (!includeHidden) append(" AND NOT CAST(e ->> 'hidden' AS BOOLEAN)")
            append(" ORDER BY sort_order ASC, code ASC")
            if (paged) append(" ${Paging.LIMIT_OFFSET}")
        }
        return handle.createQuery(sql)
            .bind("t", codeListId.tenantKey)
            .bindVersion("v", at.versionId)
            .bind("key", codeListId.key.value)
    }
}
