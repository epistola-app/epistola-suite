package app.epistola.suite.templates.queries.versions

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Gets the current draft version for a variant, if one exists.
 */
data class GetDraft(
    val tenantId: UUID,
    val templateId: UUID,
    val variantId: UUID,
) : Query<TemplateVersion?>

@Component
class GetDraftHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDraft, TemplateVersion?> {
    override fun handle(query: GetDraft): TemplateVersion? = jdbi.withHandle<TemplateVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.id, ver.variant_id, ver.version_number, ver.template_model, ver.status, ver.created_at, ver.published_at, ver.archived_at
                FROM template_versions ver
                JOIN template_variants tv ON ver.variant_id = tv.id
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE ver.variant_id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                  AND ver.status = 'draft'
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
