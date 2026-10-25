// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.TemplateStore
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class DeleteVariant(
    val variantId: VariantId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Boolean>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class DeleteVariantHandler(
    private val jdbi: Jdbi,
    private val templates: TemplateStore,
) : CommandHandler<DeleteVariant, Boolean> {
    override fun handle(command: DeleteVariant): Boolean {
        requireCatalogEditable(command.variantId.catalogId)

        return jdbi.inTransaction<Boolean, Exception> { handle ->
            val key = command.variantId.key.value
            templates.modify(handle, command.variantId.templateId, currentUserIdOrNull()?.value, null, "Variant '$key'") { template ->
                val variant = template.variant(key) ?: return@modify null
                // The default variant cannot go.
                if (variant.isDefault) throw DefaultVariantDeletionException(command.variantId.key)
                template.copy(variants = template.variants - variant)
            } != null
        }
    }
}
