// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.releases.ContentVersion
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.templates.TemplateStore
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Changes the stored model of [variant] by [change], past every command's validation: the state a
 * database written by an older release, or by hand, can hold. A template is stored as JSON (ADR 0033),
 * so a test that needs such a model writes the content itself rather than a table row.
 */
fun TemplateStore.rewriteStoredModel(jdbi: Jdbi, objectMapper: ObjectMapper, variant: VariantId, change: (ObjectNode) -> Unit) {
    jdbi.useTransaction<Exception> { handle ->
        // As the catalog's next version holds it.
        val at = ContentVersion.next(handle, variant.catalogId)
        val loaded = checkNotNull(get(handle, at, variant.templateId)) { "No template ${variant.templateKey.value}" }
        val stored = checkNotNull(loaded.template.variant(variant.key.value)) { "No variant ${variant.key.value}" }
        val model = objectMapper.valueToTree<ObjectNode>(checkNotNull(model(handle, variant.tenantKey, stored)))
        change(model)
        checkNotNull(save(handle, at, loaded, loaded.template.withVariant(variant.key.value) { it.copy(model = model) }, null))
    }
}
