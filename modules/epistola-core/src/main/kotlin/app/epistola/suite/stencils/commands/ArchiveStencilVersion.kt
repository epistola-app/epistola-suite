package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Archives a published stencil version.
 * Archived versions cannot be inserted into new templates but remain embedded
 * in templates that already use them.
 * Returns null if the version doesn't exist or is not published.
 */
data class ArchiveStencilVersion(
    val versionId: StencilVersionId,
) : Command<StencilVersion?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_PUBLISH
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class ArchiveStencilVersionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ArchiveStencilVersion, StencilVersion?> {
    override fun handle(command: ArchiveStencilVersion): StencilVersion? = jdbi.inTransaction<StencilVersion?, Exception> { handle ->
        handle.createQuery(
            """
            UPDATE stencil_versions
            SET status = 'archived', archived_at = NOW()
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId AND id = :versionId
              AND status = 'published'
            RETURNING *
            """,
        )
            .bind("tenantId", command.versionId.tenantKey)
            .bind("catalogKey", command.versionId.catalogKey)
            .bind("stencilId", command.versionId.stencilKey)
            .bind("versionId", command.versionId.key)
            .mapTo<StencilVersion>()
            .findOne()
            .orElse(null)
    }
}
