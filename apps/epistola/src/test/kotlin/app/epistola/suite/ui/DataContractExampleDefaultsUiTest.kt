// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

class DataContractExampleDefaultsUiTest : BasePlaywrightTest() {
    private val objectMapper = ObjectMapper()

    @Test
    fun `the example form shows the default of a field the example leaves out`() {
        val (tenant, template) = withMediator {
            val tenant = CreateTenant(
                id = TenantKey.of("example-defaults-ui-${System.nanoTime()}"),
                name = "Example Defaults UI Tenant",
            ).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            val template = CreateDocumentTemplate(id = templateId, name = "Example Defaults UI Template").execute()
            CreateContractVersion(templateId = templateId).execute()
            UpdateContractVersion(
                templateId = templateId,
                dataModel = objectMapper.readValue(
                    """
                    {"type":"object","required":["country"],"properties":{
                      "country":{"type":"string","default":"Netherlands"},
                      "start":{"type":"string","format":"date","default":"2026-01-01"}
                    }}
                    """.trimIndent(),
                    ObjectNode::class.java,
                ),
                dataExamples = listOf(
                    DataExample(id = "defaults-example", name = "Example", data = JsonNodeFactory.instance.objectNode()),
                ),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/data-contract?edit=true")

        val country = page.locator("#dc-field-country")
        assertThat(country).hasAttribute("placeholder", "Netherlands (default)")
        // A date input cannot show a placeholder, so the default is a hint beside it.
        assertThat(page.locator(".dc-field-default-hint")).hasText("Default: 2026-01-01")

        // Clearing a value leaves the field out again, so the default is back.
        country.fill("Belgium")
        country.press("Tab")
        assertThat(country).hasValue("Belgium")
        country.fill("")
        country.press("Tab")
        assertThat(country).hasValue("")
        assertThat(country).hasAttribute("placeholder", "Netherlands (default)")
    }
}
