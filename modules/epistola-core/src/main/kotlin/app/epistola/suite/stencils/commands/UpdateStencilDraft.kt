package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.templates.validation.ParameterSchemaValidator
import app.epistola.suite.templates.validation.PlaceholderValidator
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Updates the content of a draft stencil version. Only drafts can be updated.
 * Returns null if the version doesn't exist or is not a draft.
 */
data class UpdateStencilDraft(
    val versionId: StencilVersionId,
    val content: TemplateDocument,
    val parameterSchema: JsonNode? = null,
) : Command<StencilVersion?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class UpdateStencilDraftHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val placeholderValidator: PlaceholderValidator,
    private val parameterSchemaValidator: ParameterSchemaValidator,
) : CommandHandler<UpdateStencilDraft, StencilVersion?> {
    override fun handle(command: UpdateStencilDraft): StencilVersion? {
        requireCatalogEditable(command.versionId.tenantKey, command.versionId.catalogKey)
        placeholderValidator.validateAsStencilDefinition(command.content)
        parameterSchemaValidator.validate(command.parameterSchema)
        return jdbi.inTransaction<StencilVersion?, Exception> { handle ->
            val contentJson = objectMapper.writeValueAsString(command.content)
            val parameterSchemaJson = command.parameterSchema?.let { objectMapper.writeValueAsString(it) }

            handle.createQuery(
                """
            UPDATE stencil_versions
            SET content = :content::jsonb,
                parameter_schema = :parameterSchema::jsonb
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId AND id = :versionId
              AND status = 'draft'
            RETURNING *
            """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("stencilId", command.versionId.stencilKey)
                .bind("versionId", command.versionId.key)
                .bind("content", contentJson)
                .bind("parameterSchema", parameterSchemaJson)
                .mapTo<StencilVersion>()
                .findOne()
                .orElse(null)
        }
    }
}
