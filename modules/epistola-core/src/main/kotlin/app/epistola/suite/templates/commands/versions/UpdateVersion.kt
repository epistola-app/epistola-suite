// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.versions

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.VersionNotDraftException
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Updates a draft version's content.
 * Only draft versions can be updated; published/archived versions are immutable.
 */
data class UpdateVersion(
    val versionId: VersionId,
    val templateModel: TemplateDocument,
) : Command<TemplateVersion>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class UpdateVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateVersion, TemplateVersion> {
    override fun handle(command: UpdateVersion): TemplateVersion {
        requireCatalogEditable(command.versionId.tenantKey, command.versionId.catalogKey)
        return jdbi.inTransaction<TemplateVersion, Exception> { handle ->
            val templateModelJson = objectMapper.writeValueAsString(command.templateModel)

            // Verify version exists and is a draft
            val existing = handle.createQuery(
                """
                SELECT status FROM template_versions
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey
                  AND template_key = :templateId AND variant_key = :variantId AND id = :versionId
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .mapTo<String>()
                .findOne()
                .orElse(null)
                ?: throw VersionNotFoundException(
                    command.versionId.tenantKey,
                    command.versionId.templateKey,
                    command.versionId.variantKey,
                    command.versionId.key,
                )

            if (existing != "draft") {
                throw VersionNotDraftException(command.versionId.tenantKey, command.versionId.key)
            }

            // Update the draft
            handle.createQuery(
                """
                UPDATE template_versions
                SET template_model = :templateModel::jsonb
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey
                  AND template_key = :templateId AND variant_key = :variantId AND id = :versionId
                  AND status = 'draft'
                RETURNING *
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .bind("templateModel", templateModelJson)
                .mapTo<TemplateVersion>()
                .one()
        }
    }
}
