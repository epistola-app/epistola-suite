package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
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
    val templateId: TemplateId,
) : Query<List<PublishableVersion>>

data class PublishableVersion(
    val variantKey: VariantKey,
    val versionKey: VersionKey,
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
                    ver.variant_key,
                    ver.id as version_key,
                    ver.status
                FROM template_versions ver
                JOIN template_variants tv ON tv.tenant_key = ver.tenant_key AND tv.id = ver.variant_key
                WHERE tv.template_key = :templateId
                  AND ver.tenant_key = :tenantId
                  AND ver.status IN ('draft', 'published')
                ORDER BY ver.variant_key, ver.id DESC
                """,
        )
            .bind("templateId", query.templateId.key)
            .bind("tenantId", query.templateId.tenantKey)
            .mapTo<PublishableVersion>()
            .list()
    }
}
