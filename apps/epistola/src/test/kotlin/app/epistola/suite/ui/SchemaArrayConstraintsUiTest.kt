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
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

class SchemaArrayConstraintsUiTest : BasePlaywrightTest() {

    @Test
    fun `array field maxItems and minItems constraints are editable and validated`() {
        val (tenant, template) = withMediator {
            val tenant = CreateTenant(
                id = TenantKey.of("array-constraints-ui-${System.nanoTime()}"),
                name = "Array Constraints UI Tenant",
            ).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Array Constraints UI Template",
            ).execute()
            CreateContractVersion(templateId = templateId).execute()
            UpdateContractVersion(
                templateId = templateId,
                dataExamples = listOf(
                    DataExample(
                        id = "array-constraints-example",
                        name = "Example",
                        data = JsonNodeFactory.instance.objectNode(),
                    ),
                ),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/data-contract?edit=true")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add field to data contract")).click()
        page.locator("[data-testid=dc-field-type-select]").selectOption("array")

        val minItemsInput = page.locator("[data-testid=dc-min-items-input]")
        val maxItemsInput = page.locator("[data-testid=dc-max-items-input]")
        val saveButton = page.locator("#contract-save-controls .dc-save-btn")
        val banner = page.locator(".dc-validation-banner")

        // Valid: only minItems set.
        minItemsInput.fill("1")
        minItemsInput.press("Tab")
        assertThat(page.locator(".dc-field-error")).hasCount(0)

        // Negative minItems is rejected.
        minItemsInput.fill("-1")
        minItemsInput.press("Tab")
        assertThat(page.locator(".dc-field-error")).containsText("\"Min items\" (-1) must not be negative")
        assertThat(saveButton).isDisabled()
        assertThat(banner).containsText("validation issue")

        // Negative maxItems is rejected.
        minItemsInput.fill("0")
        minItemsInput.press("Tab")
        maxItemsInput.fill("-1")
        maxItemsInput.press("Tab")
        assertThat(page.locator(".dc-field-error")).containsText("\"Max items\" (-1) must not be negative")
        assertThat(saveButton).isDisabled()

        // maxItems below minItems is rejected.
        minItemsInput.fill("5")
        minItemsInput.press("Tab")
        maxItemsInput.fill("1")
        maxItemsInput.press("Tab")
        assertThat(page.locator(".dc-field-error"))
            .containsText("\"Max items\" (1) must not be less than \"Min items\" (5)")
        assertThat(saveButton).isDisabled()

        // A satisfiable range clears the error and unblocks save.
        maxItemsInput.fill("10")
        maxItemsInput.press("Tab")
        assertThat(page.locator(".dc-field-error")).hasCount(0)
        assertThat(banner).hasCount(0)
        assertThat(saveButton).isEnabled()
    }
}
