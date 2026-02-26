package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Gets the current draft version for a variant, if one exists.
 */
data class GetDraft(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
) : Query<TemplateVersion?>

@Component
class GetDraftHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDraft, TemplateVersion?> {
    override fun handle(query: GetDraft): TemplateVersion? = jdbi.withHandle<TemplateVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.id, ver.tenant_key, ver.variant_key, ver.template_model, ver.status, ver.created_at, ver.published_at, ver.archived_at
                FROM template_versions ver
                JOIN template_variants tv ON tv.tenant_key = ver.tenant_key AND tv.id = ver.variant_key
                WHERE ver.variant_key = :variantId
                  AND ver.tenant_key = :tenantId
                  AND ver.status = 'draft'
                  AND tv.template_key = :templateId
                """,
        )
            .bind("variantId", query.variantId)
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}
