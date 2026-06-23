package app.epistola.suite.templates.commands.versions

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Creates a new draft version for a variant.
 * Version ID is calculated automatically as MAX(id) + 1 for the variant.
 * If no templateModel is provided, creates a default empty template structure.
 * Returns null if variant doesn't exist or tenant doesn't own the template.
 * If a draft already exists for this variant, returns the existing draft (idempotent).
 * Throws exception if maximum version limit (200) is reached.
 */
data class CreateVersion(
    val variantId: VariantId,
    val templateModel: TemplateDocument? = null,
) : Command<TemplateVersion?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class CreateVersionHandler(
    private val jdbi: Jdbi,
    private val draftVersionFactory: DraftVersionFactory,
) : CommandHandler<CreateVersion, TemplateVersion?> {
    override fun handle(command: CreateVersion): TemplateVersion? {
        requireCatalogEditable(command.variantId.tenantKey, command.variantId.catalogKey)
        return jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
            draftVersionFactory.ensureDraft(handle, command.variantId, command.templateModel)
        }
    }
}
