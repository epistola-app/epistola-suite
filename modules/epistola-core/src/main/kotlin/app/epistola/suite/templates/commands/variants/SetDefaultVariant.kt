// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.TemplateStore
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.templateVariant
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
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The revision of the variant made the default, as read; null to make it the default whatever it is at. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<TemplateVariant?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class SetDefaultVariantHandler(
    private val jdbi: Jdbi,
    private val templates: TemplateStore,
) : CommandHandler<SetDefaultVariant, TemplateVariant?> {
    override fun handle(command: SetDefaultVariant): TemplateVariant? {
        requireCatalogEditable(command.variantId.catalogId)
        return jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
            val key = command.variantId.key.value
            templates.modify(handle, command.variantId.templateId, currentUserIdOrNull()?.value, command.expectedRevision, "Variant '$key'") { template ->
                template.variant(key) ?: return@modify null
                template.copy(variants = template.variants.map { it.copy(isDefault = it.key == key) })
            } ?: return@inTransaction null
            templateVariant(handle, command.variantId)
        }
    }
}
