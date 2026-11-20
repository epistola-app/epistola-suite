// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.interactive.InteractiveGeneration
import app.epistola.suite.documents.interactive.InteractiveTrigger
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.time.EpistolaClock
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.util.UUID

/**
 * The interactive generation form in a browser (contract ADR 0011): the link opens the form with the
 * fields its data contract asks for, named in words and pre-filled; filling one in, previewing and
 * submitting work under the strict CSP, and the person lands on the page saying the document is on
 * its way. A field left empty is marked only once the person tries to go on.
 */
class InteractiveFormUiTest : BasePlaywrightTest() {

    private val objectMapper = ObjectMapper()

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    @Test
    fun `a person completes the data, previews it and submits`() {
        val request = waitingRequest()

        gotoAndReady(request.interactive!!.url!!.substringAfter("https://suite.test"))

        assertThat(page.getByTestId("form-title")).hasText("Letter, case Z-1")
        assertThat(page.locator("label[for=field-name]")).containsText("Name")
        assertThat(page.locator("#field-name")).hasValue("Jane")
        assertThat(page.locator("[data-form-progress]")).hasText("1 required field left")
        page.locator("#field-motivation").fill("Because.")
        page.locator("#field-motivation").blur()
        assertThat(page.locator("[data-form-progress]")).hasText("Everything required is filled in.")

        // Wide screens render the preview by itself once nothing required is missing.
        assertThat(page.locator("[data-form-preview-frame]")).isVisible()

        page.getByTestId("form-submit").click()
        assertThat(page.getByTestId("form-closed")).hasAttribute("data-outcome", "SUBMITTED")
    }

    @Test
    fun `previewing before the data is complete marks what is missing`() {
        val request = waitingRequest()

        gotoAndReady(request.interactive!!.url!!.substringAfter("https://suite.test"))
        // Nothing marked yet: the person has not been to the empty field.
        assertThat(page.locator("#field-motivation-error")).hasCount(0)
        page.getByTestId("form-preview-refresh").click()

        assertThat(page.locator("[data-form-preview-message]")).hasText("Fill in the marked fields to see a preview.")
        assertThat(page.locator("[data-form-preview-frame]")).isHidden()
        assertThat(page.locator("#field-motivation-error")).isVisible()
    }

    @Test
    fun `submitting with a required field empty marks it, without leaving the page`() {
        val request = waitingRequest()

        gotoAndReady(request.interactive!!.url!!.substringAfter("https://suite.test"))
        page.getByTestId("form-submit").click()

        assertThat(page.locator("#field-motivation-error")).hasText("Fill this in.")
        assertThat(page.locator("#field-motivation")).isFocused()
        assertThat(page.getByTestId("form-closed")).hasCount(0)
    }

    /** A request waiting for input on a released template whose contract requires `name` and `motivation`. */
    private fun waitingRequest(): DocumentGenerationRequest = withMediator {
        val tenant = TenantKey.of("interactiveformuitest-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenant, "Form UI Tenant")
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("letter"), CatalogId.default(TenantId(tenant))))
        CreateDocumentTemplate(variant.templateId, "Letter", catalogVersion = null).execute()
        UpdateDataContract(
            templateId = variant.templateId,
            dataModel = json("""{"type": "object", "properties": {"name": {"type": "string"}, "motivation": {"type": "string"}}, "required": ["name", "motivation"]}"""),
            dataExamples = listOf(DataExample("example-1", "Example 1", json("""{"name": "Ada", "motivation": "An example."}"""))),
            catalogVersion = null,
        ).execute()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter"), catalogVersion = null).execute()
        mediator.publishAndRelease(variant)
        mediator.releaseNext(variant.templateId.catalogId)
        SaveFeatureToggle(tenant, KnownFeatures.INTERACTIVE_GENERATION, true).execute()
        // The server's request threads run on the system clock: the link is issued on it too.
        EpistolaClock.withClock(Clock.systemUTC()) {
            GenerateDocument(
                catalog = testCatalog(tenant, CatalogKey.DEFAULT),
                templateId = variant.templateId.key,
                variantId = variant.key,
                data = json("""{"name": "Jane"}"""),
                filename = "letter.pdf",
                interactive = InteractiveGeneration(InteractiveTrigger.INCOMPLETE, title = "Letter, case Z-1"),
            ).execute()
        }
    }
}
