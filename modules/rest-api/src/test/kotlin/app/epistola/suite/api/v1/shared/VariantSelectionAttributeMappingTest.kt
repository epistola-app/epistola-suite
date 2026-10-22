// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.GenerateDocumentRequest
import app.epistola.api.model.VariantSelectionAttribute
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import tools.jackson.databind.node.JsonNodeFactory
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A selection attribute names the attribute and, by the alias the rendering release declares it under
 * (contract ADR 0003), the catalog that defines it; without `catalogKey`, or with the template's own
 * key, the template's own catalog (contract `VariantSelectionAttribute`). Variants store their
 * attributes qualified by that catalog's key, which is its alias here, so the criterion is qualified
 * the same way.
 */
class VariantSelectionAttributeMappingTest {
    private val letters = catalog("letters", namespace = "acme")
    private val shared = catalog("shared", namespace = "acme")

    private fun catalog(key: String, namespace: String) = Catalog(
        id = CatalogKey.of(key),
        tenantKey = TenantKey.of("acme"),
        registry = "registry.example",
        namespace = namespace,
        name = key,
        createdAt = AT,
        updatedAt = AT,
        contentUpdatedAt = AT,
    )

    private fun criteriaFor(vararg attributes: VariantSelectionAttribute) = GenerateDocumentRequest(
        namespace = "acme",
        catalogKey = "letters",
        templateKey = "invoice",
        attributes = attributes.toList(),
        data = JsonNodeFactory.instance.objectNode(),
    ).toCommand("acme") { _, _, key -> if (key == "shared") shared else letters }.variantSelectionCriteria!!

    private companion object {
        val AT: OffsetDateTime = OffsetDateTime.parse("2026-10-20T10:00:00Z")
    }

    @Test
    fun `a key without a catalog names an attribute of the template's own catalog`() {
        val criteria = criteriaFor(VariantSelectionAttribute(key = "language", value = "nl"))

        assertEquals(mapOf("letters.language" to "nl"), criteria.requiredAttributes)
    }

    @Test
    fun `the template's own key is the template's own catalog`() {
        assertEquals(mapOf("letters.language" to "nl"), criteriaFor(VariantSelectionAttribute(key = "language", value = "nl", catalogKey = "letters")).requiredAttributes)
    }

    @Test
    fun `a key with a catalog names an attribute of that catalog`() {
        val criteria = criteriaFor(
            VariantSelectionAttribute(key = "language", value = "nl", catalogKey = "shared", required = false),
            VariantSelectionAttribute(key = "tone", value = "formal", catalogKey = "shared"),
        )

        assertEquals(mapOf("shared.language" to "nl"), criteria.optionalAttributes)
        assertEquals(mapOf("shared.tone" to "formal"), criteria.requiredAttributes)
    }
}
