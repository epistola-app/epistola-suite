// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilVersionNotFoundException
import app.epistola.suite.stencils.StencilVersionNotPublishedException
import app.epistola.suite.stencils.model.StencilVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Archives a published stencil version.
 * Archived versions cannot be inserted into new templates but remain embedded
 * in templates that already use them.
 * Throws if the version doesn't exist or is not published.
 */
data class ArchiveStencilVersion(
    val versionId: StencilVersionId,
) : Command<StencilVersion>,
    RequiresPermission {
    override val permission = Permission.STENCIL_PUBLISH
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class ArchiveStencilVersionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ArchiveStencilVersion, StencilVersion> {
    override fun handle(command: ArchiveStencilVersion): StencilVersion = jdbi.inTransaction<StencilVersion, Exception> { handle ->
        // Check version exists and its status
        val currentStatus = handle.createQuery(
            """
            SELECT status FROM stencil_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId AND id = :versionId
            """,
        )
            .bind("tenantId", command.versionId.tenantKey)
            .bind("catalogKey", command.versionId.catalogKey)
            .bind("stencilId", command.versionId.stencilKey)
            .bind("versionId", command.versionId.key)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)

        if (currentStatus == null) {
            throw StencilVersionNotFoundException(
                command.versionId.tenantKey,
                command.versionId.stencilKey,
                command.versionId.catalogKey,
                command.versionId.key,
            )
        }

        if (currentStatus != "published") {
            throw StencilVersionNotPublishedException(
                command.versionId.tenantKey,
                command.versionId.stencilKey,
                command.versionId.catalogKey,
                command.versionId.key,
            )
        }

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
            .one()
    }
}
