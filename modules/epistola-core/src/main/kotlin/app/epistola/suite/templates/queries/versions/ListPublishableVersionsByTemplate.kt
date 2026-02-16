package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.VersionStatus
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Returns draft and published versions for all variants of a template (excludes archived).
 * Used to populate version selectors in the deployment matrix.
 */
data class ListPublishableVersionsByTemplate(
    val tenantId: TenantId,
    val templateId: TemplateId,
) : Query<List<PublishableVersion>>

data class PublishableVersion(
    val variantId: VariantId,
    val versionId: VersionId,
    val status: VersionStatus,
)

@Component
class ListPublishableVersionsByTemplateHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListPublishableVersionsByTemplate, List<PublishableVersion>> {
    override fun handle(query: ListPublishableVersionsByTemplate): List<PublishableVersion> = jdbi.withHandle<List<PublishableVersion>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    ver.variant_id,
                    ver.id as version_id,
                    ver.status
                FROM template_versions ver
                JOIN template_variants tv ON tv.tenant_id = ver.tenant_id AND tv.id = ver.variant_id
                WHERE tv.template_id = :templateId
                  AND ver.tenant_id = :tenantId
                  AND ver.status IN ('draft', 'published')
                ORDER BY ver.variant_id, ver.id DESC
                """,
        )
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<PublishableVersion>()
            .list()
    }
}
