package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
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
) : Command<StencilVersion?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

@Component
class CreateStencilVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateStencilVersion, StencilVersion?> {
    override fun handle(command: CreateStencilVersion): StencilVersion? = jdbi.inTransaction<StencilVersion?, Exception> { handle ->
        // Verify stencil exists
        val stencilExists = handle.createQuery(
            "SELECT COUNT(*) > 0 FROM stencils WHERE tenant_key = :tenantId AND id = :stencilId",
        )
            .bind("tenantId", command.stencilId.tenantKey)
            .bind("stencilId", command.stencilId.key)
            .mapTo<Boolean>()
            .one()

        if (!stencilExists) return@inTransaction null

        // Check if a draft already exists (idempotent)
        val existingDraft = handle.createQuery(
            """
            SELECT * FROM stencil_versions
            WHERE tenant_key = :tenantId AND stencil_key = :stencilId AND status = 'draft'
            """,
        )
            .bind("tenantId", command.stencilId.tenantKey)
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
            WHERE tenant_key = :tenantId AND stencil_key = :stencilId
            """,
        )
            .bind("tenantId", command.stencilId.tenantKey)
            .bind("stencilId", command.stencilId.key)
            .mapTo(Int::class.java)
            .one()

        require(nextVersionId <= VersionKey.MAX_VERSION) {
            "Maximum version limit (${VersionKey.MAX_VERSION}) reached for stencil ${command.stencilId.key}"
        }

        // Use provided content or copy from latest published version
        val contentJson = if (command.content != null) {
            objectMapper.writeValueAsString(command.content)
        } else {
            handle.createQuery(
                """
                SELECT content::text FROM stencil_versions
                WHERE tenant_key = :tenantId AND stencil_key = :stencilId AND status = 'published'
                ORDER BY id DESC LIMIT 1
                """,
            )
                .bind("tenantId", command.stencilId.tenantKey)
                .bind("stencilId", command.stencilId.key)
                .mapTo(String::class.java)
                .findOne()
                .orElse(null)
                ?: error("No content provided and no published version to copy from for stencil ${command.stencilId.key}")
        }

        handle.createQuery(
            """
            INSERT INTO stencil_versions (id, tenant_key, stencil_key, content, status, created_at)
            VALUES (:id, :tenantId, :stencilId, :content::jsonb, 'draft', NOW())
            RETURNING *
            """,
        )
            .bind("id", VersionKey.of(nextVersionId))
            .bind("tenantId", command.stencilId.tenantKey)
            .bind("stencilId", command.stencilId.key)
            .bind("content", contentJson)
            .mapTo<StencilVersion>()
            .one()
    }
}
