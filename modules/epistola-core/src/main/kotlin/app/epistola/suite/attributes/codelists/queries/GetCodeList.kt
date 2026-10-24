// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetCodeList(
    val id: CodeListId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<CodeList?>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = id.tenantKey
}

@Component
class GetCodeListHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetCodeList, CodeList?> {
    override fun handle(query: GetCodeList): CodeList? = jdbi.withHandle<CodeList?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT cl.slug, cl.tenant_key, c.catalog_key AS catalog_key, c.namespace AS namespace, cl.display_name, cl.description,
                   cl.source_type, cl.source_url, cl.auth_type, cl.credential,
                   cl.last_refreshed_at, cl.last_refresh_error,
                   cl.created_at, cl.updated_at, cl.revision
            FROM code_lists cl
            JOIN catalogs c ON c.tenant_key = cl.tenant_key AND c.catalog_id = cl.catalog_id
            WHERE cl.tenant_key = :tenantKey AND cl.version_id = ${versionAt("tenantKey", "catalogKey")} AND cl.slug = :slug
            """,
        )
            .bind("tenantKey", query.id.tenantKey)
            .bindCatalog("catalogKey", query.id.catalogId)
            .bind("slug", query.id.key)
            .mapTo<CodeList>()
            .findOne()
            .orElse(null)
    }
}
