package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Updates the content of a draft stencil version. Only drafts can be updated.
 * Returns null if the version doesn't exist or is not a draft.
 */
data class UpdateStencilDraft(
    val versionId: StencilVersionId,
    val content: TemplateDocument,
) : Command<StencilVersion?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class UpdateStencilDraftHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateStencilDraft, StencilVersion?> {
    override fun handle(command: UpdateStencilDraft): StencilVersion? {
        requireCatalogEditable(command.versionId.tenantKey, command.versionId.catalogKey)
        return jdbi.inTransaction<StencilVersion?, Exception> { handle ->
        val contentJson = objectMapper.writeValueAsString(command.content)

        handle.createQuery(
            """
            UPDATE stencil_versions
            SET content = :content::jsonb
            WHERE tenant_key = :tenantId AND stencil_key = :stencilId AND id = :versionId
              AND status = 'draft'
            RETURNING *
            """,
        )
            .bind("tenantId", command.versionId.tenantKey)
            .bind("stencilId", command.versionId.stencilKey)
            .bind("versionId", command.versionId.key)
            .bind("content", contentJson)
            .mapTo<StencilVersion>()
            .findOne()
            .orElse(null)
        }
    }
}
