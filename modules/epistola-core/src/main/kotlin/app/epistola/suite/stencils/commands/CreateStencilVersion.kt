package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.templates.validation.ParameterSchemaValidator
import app.epistola.suite.templates.validation.PlaceholderValidator
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Creates a new draft version for a stencil.
 * If content is provided, uses it. Otherwise copies from the latest published version.
 * Returns null if stencil doesn't exist.
 * If a draft already exists, returns it (idempotent).
 */
data class CreateStencilVersion(
    val stencilId: StencilId,
    val content: TemplateDocument? = null,
    val parameterSchema: JsonNode? = null,
    val inheritParameterSchemaFromSource: Boolean = true,
) : Command<StencilVersion?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

@Component
class CreateStencilVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val placeholderValidator: PlaceholderValidator,
    private val parameterSchemaValidator: ParameterSchemaValidator,
) : CommandHandler<CreateStencilVersion, StencilVersion?> {
    override fun handle(command: CreateStencilVersion): StencilVersion? {
        requireCatalogEditable(command.stencilId.tenantKey, command.stencilId.catalogKey)
        if (command.content != null) placeholderValidator.validateAsStencilDefinition(command.content)
        parameterSchemaValidator.validate(command.parameterSchema)
        val auditUser = currentUserIdOrNull()?.value
        return jdbi.inTransaction<StencilVersion?, Exception> { handle ->
            // Verify stencil exists
            val stencilExists = handle.createQuery(
                "SELECT COUNT(*) > 0 FROM stencils WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND id = :stencilId",
            )
                .bind("tenantId", command.stencilId.tenantKey)
                .bind("catalogKey", command.stencilId.catalogKey)
                .bind("stencilId", command.stencilId.key)
                .mapTo<Boolean>()
                .one()

            if (!stencilExists) return@inTransaction null

            // Check if a draft already exists (idempotent)
            val existingDraft = handle.createQuery(
                """
            SELECT * FROM stencil_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId AND status = 'draft'
            """,
            )
                .bind("tenantId", command.stencilId.tenantKey)
                .bind("catalogKey", command.stencilId.catalogKey)
                .bind("stencilId", command.stencilId.key)
                .mapTo<StencilVersion>()
                .findOne()
                .orElse(null)

            if (existingDraft != null) return@inTransaction existingDraft

            // Calculate next version ID
            val nextVersionId = handle.createQuery(
                """
            SELECT COALESCE(MAX(id), 0) + 1
            FROM stencil_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId
            """,
            )
                .bind("tenantId", command.stencilId.tenantKey)
                .bind("catalogKey", command.stencilId.catalogKey)
                .bind("stencilId", command.stencilId.key)
                .mapTo(Int::class.java)
                .one()

            if (nextVersionId > VersionKey.MAX_VERSION) {
                throw ValidationException("versionId", "Maximum version limit (${VersionKey.MAX_VERSION}) reached for stencil ${command.stencilId.key}")
            }

            // When no content is provided, copy from an existing version: prefer the
            // latest published one, but fall back to the latest of any status so a
            // stencil whose versions are all archived can still be reopened for
            // editing (archiving is non-destructive). Only a stencil with no versions
            // at all has nothing to copy.
            val source = if (command.content != null) {
                null
            } else {
                handle.createQuery(
                    """
                SELECT content::text AS content, parameter_schema::text AS parameter_schema
                FROM stencil_versions
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId
                ORDER BY (status = 'published') DESC, id DESC
                LIMIT 1
                """,
                )
                    .bind("tenantId", command.stencilId.tenantKey)
                    .bind("catalogKey", command.stencilId.catalogKey)
                    .bind("stencilId", command.stencilId.key)
                    .mapToMap()
                    .findOne()
                    .orElse(null)
                    ?: throw ValidationException("content", "No content provided and the stencil ${command.stencilId.key} has no version to copy from")
            }

            val contentJson = if (command.content != null) {
                objectMapper.writeValueAsString(command.content)
            } else {
                source!!["content"].toString()
            }

            // Schema: explicit wins; otherwise carry over the copied version's schema
            // for internal draft-reopen flows unless the caller opts into contract-style
            // null/omitted semantics.
            val parameterSchemaJson = when {
                command.parameterSchema != null -> objectMapper.writeValueAsString(command.parameterSchema)
                command.content != null || !command.inheritParameterSchemaFromSource -> null
                else -> source!!["parameter_schema"] as? String
            }

            handle.createQuery(
                """
            INSERT INTO stencil_versions (id, tenant_key, catalog_key, stencil_key, content, parameter_schema, status, created_at, created_by)
            VALUES (:id, :tenantId, :catalogKey, :stencilId, :content::jsonb, :parameterSchema::jsonb, 'draft', NOW(), :createdBy)
            RETURNING *
            """,
            )
                .bind("id", VersionKey.of(nextVersionId))
                .bind("tenantId", command.stencilId.tenantKey)
                .bind("catalogKey", command.stencilId.catalogKey)
                .bind("stencilId", command.stencilId.key)
                .bind("content", contentJson)
                .bind("parameterSchema", parameterSchemaJson)
                .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                .mapTo<StencilVersion>()
                .one()
        }
    }
}
