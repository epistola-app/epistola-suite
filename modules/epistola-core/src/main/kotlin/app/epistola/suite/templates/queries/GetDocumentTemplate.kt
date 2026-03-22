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
                SELECT id, tenant_key, name, theme_key, schema, data_model, data_examples, pdfa_enabled, created_at, last_modified
                FROM document_templates
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}
