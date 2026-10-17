// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.services.TemplateDocumentPreparation
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Replaces the working model of a variant (contract ADR 0002: the catalog release is the only
 * version, so a variant has one model to edit). The model is validated and prepared as every write
 * is, stencil instances stored by reference. An edit makes a ready mark on the variant stale.
 *
 * @return the model as stored.
 */
data class UpdateVariantContent(
    val variantId: VariantId,
    val templateModel: TemplateDocument,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<TemplateDocument>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class UpdateVariantContentHandler(
    private val jdbi: Jdbi,
    private val templateDocumentPreparation: TemplateDocumentPreparation,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateVariantContent, TemplateDocument> {
    override fun handle(command: UpdateVariantContent): TemplateDocument {
        requireCatalogEditable(command.variantId.tenantKey, command.variantId.catalogKey)
        val prepared = templateDocumentPreparation.prepareDraft(command.templateModel, command.variantId.catalogKey)
        val updated = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE template_variants
                SET template_model = :templateModel::jsonb, referenced_paths = :referencedPaths::jsonb, updated_at = NOW()
                WHERE tenant_key = :tenantKey AND id = :variantKey
                  AND version_id = ${versionAt("tenantKey", "catalogKey")} AND template_resource_id = ${templateAtAddress("tenantKey", "catalogKey", "templateKey")}
                """,
            )
                .bind("tenantKey", command.variantId.tenantKey)
                .bind("catalogKey", command.variantId.catalogKey)
                .bind("templateKey", command.variantId.templateKey)
                .bind("variantKey", command.variantId.key)
                .bind("templateModel", prepared.templateModelJson)
                .bind("referencedPaths", prepared.referencedPathsJson)
                .execute()
        }
        if (updated == 0) {
            throw TemplateVariantNotFoundException(command.variantId.tenantKey, command.variantId.templateKey, command.variantId.key)
        }
        return objectMapper.readValue(prepared.templateModelJson, TemplateDocument::class.java)
    }
}
