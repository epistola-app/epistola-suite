package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Publishes a draft stencil version, making it available for insertion into templates.
 * Validates that the content does not contain nested stencil references.
 * Returns null if the version doesn't exist or is not a draft.
 */
data class PublishStencilVersion(
    val versionId: StencilVersionId,
) : Command<StencilVersion?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_PUBLISH
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class PublishStencilVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<PublishStencilVersion, StencilVersion?> {
    override fun handle(command: PublishStencilVersion): StencilVersion? = jdbi.inTransaction<StencilVersion?, Exception> { handle ->
        // Fetch the draft version
        val version = handle.createQuery(
            """
            SELECT * FROM stencil_versions
            WHERE tenant_key = :tenantId AND stencil_key = :stencilId AND id = :versionId
              AND status = 'draft'
            """,
        )
            .bind("tenantId", command.versionId.tenantKey)
            .bind("stencilId", command.versionId.stencilKey)
            .bind("versionId", command.versionId.key)
            .mapTo<StencilVersion>()
            .findOne()
            .orElse(null) ?: return@inTransaction null

        // Validate no nested stencil components
        validateNoNestedStencilRefs(version)

        // Publish: freeze the content
        handle.createQuery(
            """
            UPDATE stencil_versions
            SET status = 'published', published_at = NOW()
            WHERE tenant_key = :tenantId AND stencil_key = :stencilId AND id = :versionId
            RETURNING *
            """,
        )
            .bind("tenantId", command.versionId.tenantKey)
            .bind("stencilId", command.versionId.stencilKey)
            .bind("versionId", command.versionId.key)
            .mapTo<StencilVersion>()
            .one()
    }

    /**
     * Validates that no node in the content is a stencil component, preventing nesting.
     */
    private fun validateNoNestedStencilRefs(content: StencilVersion) {
        val stencilNodes = content.content.nodes.values.filter { it.type == "stencil" }
        if (stencilNodes.isNotEmpty()) {
            val nodeIds = stencilNodes.map { it.id }
            throw ValidationException(
                "content",
                "Stencil content cannot contain nested stencil components. " +
                    "Stencil nodes found: ${nodeIds.joinToString(", ")}",
            )
        }
    }
}
