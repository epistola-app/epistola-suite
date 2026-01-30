package app.epistola.suite.templates.queries.variants

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.TemplateVariant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListVariants(
    val tenantId: Long,
    val templateId: Long,
) : Query<List<TemplateVariant>>

@Component
class ListVariantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListVariants, List<TemplateVariant>> {
    override fun handle(query: ListVariants): List<TemplateVariant> = jdbi.withHandle<List<TemplateVariant>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT tv.id, tv.template_id, tv.tags, tv.created_at, tv.last_modified
                FROM template_variants tv
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                ORDER BY tv.created_at ASC
                """,
        )
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<TemplateVariant>()
            .list()
    }
}
