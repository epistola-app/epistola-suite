// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.GenerateDocumentRequest
import app.epistola.api.model.VariantSelectionAttribute
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import tools.jackson.databind.node.JsonNodeFactory
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A selection attribute names the attribute and the catalog that defines it; without `catalogKey`,
 * the template's own catalog (contract `VariantSelectionAttribute`). Variants store their
 * attributes qualified by that catalog, so the criterion is qualified the same way.
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
        type = CatalogType.AUTHORED,
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
    fun `a key with a catalog names an attribute of that catalog`() {
        val criteria = criteriaFor(
            VariantSelectionAttribute(key = "language", value = "nl", catalogKey = "shared", required = false),
            VariantSelectionAttribute(key = "tone", value = "formal", catalogKey = "shared", namespace = "acme"),
        )

        assertEquals(mapOf("shared.language" to "nl"), criteria.optionalAttributes)
        assertEquals(mapOf("shared.tone" to "formal"), criteria.requiredAttributes)
    }
}
