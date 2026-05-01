package app.epistola.suite.templates.queries

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
) : Query<DocumentTemplate?>,
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
                SELECT dt.id, dt.tenant_key, dt.catalog_key, c.type AS catalog_type, dt.name, dt.theme_key, dt.theme_catalog_key, dt.pdfa_enabled, dt.created_at, dt.last_modified
                FROM document_templates dt
                JOIN catalogs c ON c.tenant_key = dt.tenant_key AND c.id = dt.catalog_key
                WHERE dt.id = :id AND dt.tenant_key = :tenantId AND dt.catalog_key = :catalogKey
                """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .bind("catalogKey", query.id.catalogKey)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}
