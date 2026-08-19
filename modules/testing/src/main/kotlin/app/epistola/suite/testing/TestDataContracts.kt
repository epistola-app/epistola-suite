// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.DataExample
import tools.jackson.databind.node.JsonNodeFactory

/** Gives general-purpose template fixtures the minimum publishable data contract. */
fun ensureRequiredDataExample(templateId: TemplateId) {
    UpdateContractVersion(
        templateId = templateId,
        dataExamples = listOf(
            DataExample(
                id = "example-1",
                name = "Example 1",
                data = JsonNodeFactory.instance.objectNode(),
            ),
        ),
    ).execute()
}

fun DocumentTemplate.withRequiredDataExample(): DocumentTemplate = apply {
    ensureRequiredDataExample(
        TemplateId(
            id,
            CatalogId(catalogKey, TenantId(tenantKey)),
        ),
    )
}
