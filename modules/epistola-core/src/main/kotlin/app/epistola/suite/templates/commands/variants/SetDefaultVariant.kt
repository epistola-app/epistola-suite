// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Sets a variant as the default for its template.
 * Clears the default flag on the previous default variant.
 * Returns null if the variant is not found.
 */
data class SetDefaultVariant(
    val variantId: VariantId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
    /** The revision of the variant made the default, as read; null to make it the default whatever it is at. */
    val expectedRevision: Long? = null,
) : CatalogVersionScoped,
    Command<TemplateVariant?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class SetDefaultVariantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetDefaultVariant, TemplateVariant?> {
    override fun handle(command: SetDefaultVariant): TemplateVariant? {
        requireCatalogEditable(command.variantId.tenantKey, command.variantId.catalogKey)
        return setDefault(command)
    }

    private fun setDefault(command: SetDefaultVariant): TemplateVariant? = jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
        // Verify variant belongs to the template and tenant, at the revision the caller read.
        val current = variantRevision(handle, command.variantId) ?: return@inTransaction null
        Revisions.refuse("Variant '${command.variantId.key.value}'", command.expectedRevision, current)

        // Clear default on current default variant for this template
        handle.createUpdate(
            """
                UPDATE template_variants
                SET is_default = FALSE
                WHERE tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")} AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")} AND is_default = TRUE
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("catalogKey", command.variantId.catalogKey)
            .bind("templateId", command.variantId.templateKey)
            .execute()

        // Set the new default and return it
        handle.createQuery(
            """
                UPDATE template_variants
                SET is_default = TRUE
                WHERE tenant_key = :tenantId AND id = :variantId
                  AND version_id = ${versionAt("tenantId", "catalogKey")} AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                RETURNING *, CAST(:catalogKey AS TEXT) AS catalog_key, CAST(:templateId AS TEXT) AS template_key
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("catalogKey", command.variantId.catalogKey)
            .bind("templateId", command.variantId.templateKey)
            .bind("variantId", command.variantId.key)
            .mapTo<TemplateVariant>()
            .findOne()
            .orElse(null)
    }
}
