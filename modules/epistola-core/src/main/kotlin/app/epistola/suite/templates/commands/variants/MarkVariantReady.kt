// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilFitCheck
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Records that the variant's current content, with the template's shared fields and data contract,
 * is reviewed and may be released (contract `markVariantReady`). Refused when the model could not
 * be released: when it is not publishable, or a stencil instance in it does not fit its stencil.
 * Marking content that is already ready changes nothing; the next edit makes the mark stale.
 */
data class MarkVariantReady(
    val variantId: VariantId,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class MarkVariantReadyHandler(
    private val jdbi: Jdbi,
    private val readyMarks: ReadyMarks,
    private val validator: TemplateDocumentValidator,
    private val stencilFit: StencilFitCheck,
    private val objectMapper: ObjectMapper,
) : CommandHandler<MarkVariantReady, Unit> {
    override fun handle(command: MarkVariantReady) {
        val variant = command.variantId
        requireCatalogEditable(variant.tenantKey, variant.catalogKey)
        jdbi.useTransaction<Exception> { handle ->
            val row = handle.createQuery(
                """
                SELECT template_resource_id, template_model::text AS template_model FROM template_variants
                WHERE tenant_key = :t AND id = :v AND template_resource_id = ${templateAtAddress("t", "c", "k")}
                FOR UPDATE
                """,
            )
                .bind("t", variant.tenantKey)
                .bind("c", variant.catalogKey)
                .bind("k", variant.templateKey)
                .bind("v", variant.key)
                .map { rs, _ -> rs.getObject("template_resource_id", UUID::class.java) to rs.getString("template_model") }
                .findOne()
                .orElseThrow { TemplateVariantNotFoundException(variant.tenantKey, variant.templateKey, variant.key) }
            val model = objectMapper.readValue(row.second, TemplateDocument::class.java)
            validator.validateTemplatePublishable(model)
            stencilFit.requireInstancesFit(variant.tenantKey, variant.catalogKey.value, model, "Cannot mark variant '${variant.key.value}' ready")
            readyMarks.mark(handle, variant.tenantKey, row.first, variant.key.value)
        }
    }
}
