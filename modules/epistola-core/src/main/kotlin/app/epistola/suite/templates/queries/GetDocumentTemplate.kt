// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.TEMPLATE_COLUMNS
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetDocumentTemplate(
    val id: TemplateId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
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
                SELECT $TEMPLATE_COLUMNS
                FROM version_templates dt
                WHERE dt.id = :id AND dt.tenant_key = :tenantId AND dt.version_id = :v
                """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .bindVersion("v", handle.versionOf(query.id.catalogId, query).versionId)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}
