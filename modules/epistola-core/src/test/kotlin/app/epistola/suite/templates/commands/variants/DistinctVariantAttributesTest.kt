// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Two variants of one template with the same attribute set can never be told apart by attribute
 * selection: every request that matches one matches the other with the same score, and generation
 * fails with `AMBIGUOUS_VARIANT`. Create and update refuse it up front instead.
 *
 * An empty set is exempt. A variant without attributes never matches a required criterion, so it is
 * only reachable by its id, and a template may have several of them.
 */
class DistinctVariantAttributesTest : IntegrationTestBase() {

    private fun templateWithLanguages(): TemplateId = withMediator {
        val tenantId = TenantId(createTenant("Distinct Variants").id)
        mediator.send(
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("lang"), CatalogId.default(tenantId)),
                displayName = "Language",
                allowedValues = listOf("dutch", "english"),
            ),
        )
        mediator.send(
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("brand"), CatalogId.default(tenantId)),
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ),
        )
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        mediator.send(CreateDocumentTemplate(id = templateId, name = "Invoice"))
        templateId
    }

    private fun createVariant(templateId: TemplateId, title: String, attributes: Map<String, String>) = withMediator {
        mediator.send(
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                title = title,
                description = null,
                attributes = attributes,
            ),
        )!!
    }

    @Test
    fun `creating a variant with a sibling's attribute set is refused, naming the sibling`() {
        val templateId = templateWithLanguages()
        val dutch = createVariant(templateId, "Dutch", mapOf("lang" to "dutch", "brand" to "acme"))

        val thrown = assertThrows<ValidationException> {
            createVariant(templateId, "Also Dutch", mapOf("brand" to "acme", "lang" to "dutch"))
        }

        assertThat(thrown.field).isEqualTo("attributes")
        assertThat(thrown.message).contains(dutch.id.value)
    }

    @Test
    fun `updating a variant to a sibling's attribute set is refused`() {
        val templateId = templateWithLanguages()
        createVariant(templateId, "Dutch", mapOf("lang" to "dutch"))
        val english = createVariant(templateId, "English", mapOf("lang" to "english"))

        val thrown = assertThrows<ValidationException> {
            withMediator {
                mediator.send(UpdateVariant(VariantId(english.id, templateId), title = "English", attributes = mapOf("lang" to "dutch")))
            }
        }

        assertThat(thrown.field).isEqualTo("attributes")
    }

    @Test
    fun `updating a variant without changing its own attribute set is allowed`() {
        val templateId = templateWithLanguages()
        val dutch = createVariant(templateId, "Dutch", mapOf("lang" to "dutch"))

        val updated = withMediator {
            mediator.send(UpdateVariant(VariantId(dutch.id, templateId), title = "Nederlands", attributes = mapOf("lang" to "dutch")))
        }

        assertThat(updated!!.title).isEqualTo("Nederlands")
    }

    @Test
    fun `sets that differ only by an extra attribute are distinct`() {
        val templateId = templateWithLanguages()
        createVariant(templateId, "Dutch", mapOf("lang" to "dutch"))

        val dutchAcme = createVariant(templateId, "Dutch Acme", mapOf("lang" to "dutch", "brand" to "acme"))

        assertThat(dutchAcme.attributes).containsEntry("brand", "acme")
    }

    @Test
    fun `several variants without attributes are allowed`() {
        val templateId = templateWithLanguages()

        // The template's initial variant already has an empty set.
        createVariant(templateId, "Draft one", emptyMap())
        val second = createVariant(templateId, "Draft two", emptyMap())

        assertThat(second.attributes).isEmpty()
    }

    @Test
    fun `the same attribute set on variants of different templates is allowed`() {
        val templateId = templateWithLanguages()
        createVariant(templateId, "Dutch", mapOf("lang" to "dutch"))
        val otherTemplate = TemplateId(TestIdHelpers.nextTemplateId(), templateId.catalogId)
        withMediator { mediator.send(CreateDocumentTemplate(id = otherTemplate, name = "Reminder")) }

        val otherDutch = createVariant(otherTemplate, "Dutch", mapOf("lang" to "dutch"))

        assertThat(otherDutch.attributes).containsEntry("lang", "dutch")
    }
}
