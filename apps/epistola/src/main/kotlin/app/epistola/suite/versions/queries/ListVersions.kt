package app.epistola.suite.versions.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.versions.VersionSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListVersions(
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
) : Query<List<VersionSummary>>

@Component
class ListVersionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListVersions, List<VersionSummary>> {
    override fun handle(query: ListVersions): List<VersionSummary> = jdbi.withHandle<List<VersionSummary>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.id, ver.variant_id, ver.version_number, ver.status, ver.created_at, ver.published_at, ver.archived_at
                FROM template_versions ver
                JOIN template_variants tv ON ver.variant_id = tv.id
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE ver.variant_id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                ORDER BY
                    CASE ver.status WHEN 'draft' THEN 0 ELSE 1 END,
                    ver.version_number DESC NULLS FIRST
                """,
        )
            .bind("variantId", query.variantId)
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<VersionSummary>()
            .list()
    }
}
