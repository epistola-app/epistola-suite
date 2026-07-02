package app.epistola.suite.templates.commands.versions

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DraftHasNoPublishedBaseException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Discards the variant's draft, reverting it to the last published version.
 *
 * Deletes the single `status = 'draft'` row for the variant (the
 * `idx_one_draft_per_variant` invariant guarantees there is at most one). After
 * this the variant surfaces only its published/archived versions, so the last
 * published version becomes the effective content again.
 *
 * Only allowed when the variant has at least one published version — a draft
 * that has never been published has no "last published version" to return to and
 * throws [DraftHasNoPublishedBaseException]. Discarding when no draft exists is a
 * no-op (idempotent).
 */
data class DiscardDraft(
    val variantId: VariantId,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class DiscardDraftHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DiscardDraft, Unit> {
    override fun handle(command: DiscardDraft) {
        val variantId = command.variantId
        requireCatalogEditable(variantId.tenantKey, variantId.catalogKey)
        jdbi.useTransaction<Exception> { handle ->
            val hasPublished = handle.createQuery(
                """
                SELECT 1 FROM template_versions
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey
                  AND template_key = :templateId AND variant_key = :variantId
                  AND status = 'published'
                LIMIT 1
                """,
            )
                .bind("tenantId", variantId.tenantKey)
                .bind("catalogKey", variantId.catalogKey)
                .bind("templateId", variantId.templateKey)
                .bind("variantId", variantId.key)
                .mapTo(Int::class.java)
                .findOne()
                .isPresent

            if (!hasPublished) {
                throw DraftHasNoPublishedBaseException(variantId.tenantKey, variantId.key)
            }

            handle.createUpdate(
                """
                DELETE FROM template_versions
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey
                  AND template_key = :templateId AND variant_key = :variantId
                  AND status = 'draft'
                """,
            )
                .bind("tenantId", variantId.tenantKey)
                .bind("catalogKey", variantId.catalogKey)
                .bind("templateId", variantId.templateKey)
                .bind("variantId", variantId.key)
                .execute()
        }
    }
}
