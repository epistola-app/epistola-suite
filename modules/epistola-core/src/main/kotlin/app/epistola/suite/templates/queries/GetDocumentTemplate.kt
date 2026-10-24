// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetDocumentTemplate(
    val id: TemplateId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<DocumentTemplate?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class GetDocumentTemplateHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDocumentTemplate, DocumentTemplate?> {
    override fun handle(query: GetDocumentTemplate): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT dt.id, dt.tenant_key, c.catalog_key AS catalog_key, c.namespace AS namespace, dt.name, th.id AS theme_key, th_cat.catalog_key AS theme_catalog_key, th_cat.namespace AS theme_catalog_namespace, dt.pdfa_enabled, dt.description, dt.created_at, dt.updated_at, dt.created_by, dt.updated_by, dt.revision
                FROM document_templates dt
                JOIN catalogs c ON c.tenant_key = dt.tenant_key AND c.catalog_id = dt.catalog_id
                LEFT JOIN themes th ON th.tenant_key = dt.tenant_key AND th.resource_id = dt.theme_resource_id
                    AND (th.version_id = dt.version_id OR (th.catalog_id <> dt.catalog_id AND ${inCurrentVersion("th")}))
                LEFT JOIN catalogs th_cat ON th_cat.tenant_key = th.tenant_key AND th_cat.catalog_id = th.catalog_id
                WHERE dt.id = :id AND dt.tenant_key = :tenantId AND dt.version_id = ${versionAt("tenantId", "catalogKey")}
                """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .bindCatalog("catalogKey", query.id.catalogId)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}
