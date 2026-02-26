package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.VersionSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListVersions(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
) : Query<List<VersionSummary>>

@Component
class ListVersionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListVersions, List<VersionSummary>> {
    override fun handle(query: ListVersions): List<VersionSummary> = jdbi.withHandle<List<VersionSummary>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.id, ver.tenant_key, ver.variant_key, ver.status, ver.created_at, ver.published_at, ver.archived_at
                FROM template_versions ver
                JOIN template_variants tv ON tv.tenant_key = ver.tenant_key AND tv.id = ver.variant_key
                WHERE ver.variant_key = :variantId
                  AND ver.tenant_key = :tenantId
                  AND tv.template_key = :templateId
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
