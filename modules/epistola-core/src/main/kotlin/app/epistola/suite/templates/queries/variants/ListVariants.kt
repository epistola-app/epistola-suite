package app.epistola.suite.templates.queries.variants

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.TemplateVariant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListVariants(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
) : Query<List<TemplateVariant>>

@Component
class ListVariantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListVariants, List<TemplateVariant>> {
    override fun handle(query: ListVariants): List<TemplateVariant> = jdbi.withHandle<List<TemplateVariant>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT tv.id, tv.tenant_key, tv.template_key, tv.title, tv.description, tv.attributes, tv.is_default, tv.created_at, tv.last_modified
                FROM template_variants tv
                WHERE tv.template_key = :templateId
                  AND tv.tenant_key = :tenantId
                ORDER BY tv.is_default DESC, tv.created_at ASC
                """,
        )
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<TemplateVariant>()
            .list()
    }
}
