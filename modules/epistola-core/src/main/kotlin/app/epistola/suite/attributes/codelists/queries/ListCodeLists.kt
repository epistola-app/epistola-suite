// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists code lists owned by a tenant, optionally filtered to a single catalog.
 *
 * Whether a row can be edited is the owning catalog's editability
 * (`ListCatalogEditability`, ADR 0032), not a field of the row. Entries are
 * not fetched here; use `ListCodeListEntries` for that.
 */
data class ListCodeLists(
    val tenantId: TenantId,
    val catalogKey: CatalogKey? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<List<CodeList>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListCodeListsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCodeLists, List<CodeList>> {
    override fun handle(query: ListCodeLists): List<CodeList> = jdbi.withHandle<List<CodeList>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT cl.slug, cl.tenant_key, c.catalog_key AS catalog_key, cl.display_name, cl.description,
                       cl.source_type, cl.source_url, cl.auth_type, cl.credential,
                       cl.last_refreshed_at, cl.last_refresh_error,
                       cl.created_at, cl.updated_at, cl.revision
                FROM code_lists cl
                JOIN catalogs c ON c.tenant_key = cl.tenant_key AND c.catalog_id = cl.catalog_id
                WHERE cl.tenant_key = :tenantId
                  AND ${inCurrentVersion("cl")}
                """,
            )
            if (query.catalogKey != null) {
                append(" AND cl.version_id = ${versionAt("tenantId", "catalogKey")}")
            }
            append(" ORDER BY cl.display_name ASC")
        }

        val q = handle.createQuery(sql).bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            q.bind("catalogKey", query.catalogKey)
        }
        q.mapTo<CodeList>().list()
    }
}
