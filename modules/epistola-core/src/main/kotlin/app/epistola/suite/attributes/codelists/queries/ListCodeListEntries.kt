// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.BoundCodeListVersion
import app.epistola.suite.attributes.codelists.codeListAt
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.VersionReads
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /**
     * The catalog of the attribute bound to the list, when one is: its declarations say which
     * version of the list's catalog it means (BoundCodeListVersion).
     */
    val boundFrom: CatalogId? = null,
) : ReleaseReadable,
    Query<List<CodeListEntry>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = codeListId.tenantKey
}

@Component
class ListCodeListEntriesHandler(
    private val jdbi: Jdbi,
    private val boundVersion: BoundCodeListVersion,
) : QueryHandler<ListCodeListEntries, List<CodeListEntry>> {
    override fun handle(query: ListCodeListEntries): List<CodeListEntry> = jdbi.withHandle<List<CodeListEntry>, Exception> { outer ->
        val selection = query.boundFrom?.let { boundVersion.of(outer, it, query.codeListId.catalogId) }
        VersionReads.at(outer, selection) { handle -> entries(handle, query) }
    }

    private fun entries(handle: org.jdbi.v3.core.Handle, query: ListCodeListEntries): List<CodeListEntry> {
        // The entries of the content, in SQL: filtered and ordered there, never in memory (ADR 0033).
        val sql = buildString {
            append(
                """
                SELECT e ->> 'code' AS code, e ->> 'label' AS label,
                       CAST(e ->> 'sortOrder' AS INTEGER) AS sort_order, CAST(e ->> 'hidden' AS BOOLEAN) AS hidden
                FROM current_code_lists t
                CROSS JOIN LATERAL jsonb_array_elements(t.entries) e
                WHERE ${codeListAt()}
                """,
            )
            if (!query.includeHidden) append(" AND NOT CAST(e ->> 'hidden' AS BOOLEAN)")
            append(" ORDER BY sort_order ASC, code ASC")
        }
        return handle.createQuery(sql)
            .bind("t", query.codeListId.tenantKey)
            .bindCatalog("c", query.codeListId.catalogId)
            .bind("key", query.codeListId.key.value)
            .mapTo<CodeListEntry>()
            .list()
    }
}
