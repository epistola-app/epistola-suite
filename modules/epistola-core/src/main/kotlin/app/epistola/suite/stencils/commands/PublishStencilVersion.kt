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
import app.epistola.suite.stencils.StencilVersionNotDraftException
import app.epistola.suite.stencils.StencilVersionNotFoundException
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.stencils.model.StencilVersionStatus
import app.epistola.suite.templates.validation.ParameterSchemaValidator
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Publishes a draft stencil version, making it available for insertion into templates.
 * Re-validates portable stencil content before making it immutable.
 * Throws if the version doesn't exist or is not a draft.
 */
data class PublishStencilVersion(
    val versionId: StencilVersionId,
) : Command<StencilVersion>,
    RequiresPermission {
    override val permission = Permission.STENCIL_PUBLISH
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class PublishStencilVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val templateDocumentValidator: TemplateDocumentValidator,
    private val parameterSchemaValidator: ParameterSchemaValidator,
) : CommandHandler<PublishStencilVersion, StencilVersion> {
    override fun handle(command: PublishStencilVersion): StencilVersion = jdbi.inTransaction<StencilVersion, Exception> { handle ->
        // Fetch the draft version
        val version = handle.createQuery(
            """
            SELECT * FROM stencil_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId AND id = :versionId
            """,
        )
            .bind("tenantId", command.versionId.tenantKey)
            .bind("catalogKey", command.versionId.catalogKey)
            .bind("stencilId", command.versionId.stencilKey)
            .bind("versionId", command.versionId.key)
            .mapTo<StencilVersion>()
            .findOne()
            .orElse(null)
            ?: throw StencilVersionNotFoundException(
                command.versionId.tenantKey,
                command.versionId.stencilKey,
                command.versionId.catalogKey,
                command.versionId.key,
            )

        if (version.status != StencilVersionStatus.DRAFT) {
            throw StencilVersionNotDraftException(
                command.versionId.tenantKey,
                command.versionId.stencilKey,
                command.versionId.catalogKey,
                command.versionId.key,
            )
        }

        // Re-validate at the immutable boundary so legacy or imported drafts
        // cannot bypass the current document policy.
        templateDocumentValidator.validateStencilPublishable(version.content)
        parameterSchemaValidator.validate(version.parameterSchema)

        // Publish: freeze the content
        handle.createQuery(
            """
            UPDATE stencil_versions
            SET status = 'published', published_at = NOW()
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId AND id = :versionId
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
