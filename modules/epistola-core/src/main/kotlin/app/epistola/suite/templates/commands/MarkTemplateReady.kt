// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** Marks every variant of the template ready, with its data contract, in one action (contract `markTemplateReady`). */
data class MarkTemplateReady(
    val templateId: TemplateId,
) : Command<Unit>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class MarkTemplateReadyHandler(
    private val jdbi: Jdbi,
) : CommandHandler<MarkTemplateReady, Unit> {
    override fun handle(command: MarkTemplateReady) {
        val template = command.templateId
        val variants = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery(
                "SELECT id FROM template_variants WHERE tenant_key = :t AND template_resource_id = ${templateAtAddress("t", "c", "k")} ORDER BY id",
            )
                .bind("t", template.tenantKey)
                .bind("c", template.catalogKey)
                .bind("k", template.key)
                .mapTo(String::class.java)
                .list()
        }
        if (variants.isEmpty()) throw TemplateNotFoundException(template.tenantKey, template.key)
        variants.forEach { MarkVariantReady(VariantId(VariantKey.of(it), template)).execute() }
    }
}
