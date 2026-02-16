package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.VersionSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListVersions(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
) : Query<List<VersionSummary>>

@Component
class ListVersionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListVersions, List<VersionSummary>> {
    override fun handle(query: ListVersions): List<VersionSummary> = jdbi.withHandle<List<VersionSummary>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.id, ver.variant_id, ver.status, ver.created_at, ver.published_at, ver.archived_at
                FROM template_versions ver
                WHERE ver.variant_id = :variantId
                  AND ver.tenant_id = :tenantId
                ORDER BY
                    CASE ver.status WHEN 'draft' THEN 0 ELSE 1 END,
                    ver.id DESC
                """,
        )
            .bind("variantId", query.variantId)
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<VersionSummary>()
            .list()
    }
}
