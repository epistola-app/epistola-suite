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
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class UpdateVariant(
    val variantId: VariantId,
    val title: String,
    val attributes: Map<String, String>,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The variant's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<TemplateVariant?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey

    init {
        validate("title", title.isNotBlank()) { "Title is required" }
        validate("title", title.length <= MAX_NAME_LENGTH) { "Title must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class UpdateVariantHandler(
    private val jdbi: Jdbi,
    private val usedCatalogs: app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer,
    private val templates: TemplateStore,
) : CommandHandler<UpdateVariant, TemplateVariant?> {
    override fun handle(command: UpdateVariant): TemplateVariant? {
        requireCatalogEditable(command.variantId.catalogId)

        // Validate attributes against the tenant's attribute definitions
        validateAttributes(command.variantId.tenantId, command.attributes)
        // An attribute of another catalog names it by alias (`system.locale`): that catalog is declared now,
        // as every write that starts using another catalog declares it (contract ADR 0003).
        usedCatalogs.declareAttributes(command.variantId.catalogId, command.attributes.keys)

        return jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
            val key = command.variantId.key.value
            templates.modifyVariant(handle, command.variantId, currentUserIdOrNull()?.value, command.expectedRevision, "Variant '$key'") { template ->
                template.variant(key) ?: return@modifyVariant null
                requireDistinctAttributeSet(template, key, command.attributes)
                template.withVariant(key) { it.copy(title = command.title, attributes = command.attributes) }
            } ?: return@inTransaction null
            templateVariant(handle, command.variantId)
        }
    }
}
