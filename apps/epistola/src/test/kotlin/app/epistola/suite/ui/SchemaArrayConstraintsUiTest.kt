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

    @Test
    fun `a scalar field default value is editable and validated`() {
        val (tenant, template) = withMediator {
            val tenant = CreateTenant(
                id = TenantKey.of("default-value-ui-${System.nanoTime()}"),
                name = "Default Value UI Tenant",
            ).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Default Value UI Template",
            ).execute()
            CreateContractVersion(templateId = templateId).execute()
            UpdateContractVersion(
                templateId = templateId,
                dataExamples = listOf(
                    DataExample(
                        id = "default-value-example",
                        name = "Example",
                        data = JsonNodeFactory.instance.objectNode(),
                    ),
                ),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/data-contract?edit=true")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add field to data contract")).click()
        page.locator("[data-testid=dc-field-type-select]").selectOption("integer")

        val minimumInput = page.locator("[data-testid=dc-minimum-input]")
        val maximumInput = page.locator("[data-testid=dc-maximum-input]")
        val defaultValueInput = page.locator("[data-testid=dc-default-value-input]")
        val saveButton = page.locator("#contract-save-controls .dc-save-btn")
        val banner = page.locator(".dc-validation-banner")

        minimumInput.fill("18")
        minimumInput.press("Tab")
        maximumInput.fill("65")
        maximumInput.press("Tab")

        // A default outside [minimum, maximum] is rejected.
        defaultValueInput.fill("10")
        defaultValueInput.press("Tab")
        assertThat(page.locator(".dc-field-error")).containsText("\"Default value\" must be >= 18")
        assertThat(saveButton).isDisabled()
        assertThat(banner).containsText("validation issue")

        // A default inside the range clears the error and unblocks save.
        defaultValueInput.fill("30")
        defaultValueInput.press("Tab")
        assertThat(page.locator(".dc-field-error")).hasCount(0)
        assertThat(banner).hasCount(0)
        assertThat(saveButton).isEnabled()
    }

    @Test
    fun `a date-time field default value control is a native datetime-local input`() {
        val (tenant, template) = withMediator {
            val tenant = CreateTenant(
                id = TenantKey.of("default-datetime-ui-${System.nanoTime()}"),
                name = "Default Datetime UI Tenant",
            ).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Default Datetime UI Template",
            ).execute()
            CreateContractVersion(templateId = templateId).execute()
            UpdateContractVersion(
                templateId = templateId,
                dataExamples = listOf(
                    DataExample(
                        id = "default-datetime-example",
                        name = "Example",
                        data = JsonNodeFactory.instance.objectNode(),
                    ),
                ),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/data-contract?edit=true")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add field to data contract")).click()
        page.locator("[data-testid=dc-field-type-select]").selectOption("datetime")

        val defaultValueInput = page.locator("[data-testid=dc-default-value-input]")
        val saveButton = page.locator("#contract-save-controls .dc-save-btn")

        // Only a real browser confirms the picker actually accepts and keeps a
        // datetime-local value — happy-dom does not model native date input behavior.
        assertThat(defaultValueInput).hasAttribute("type", "datetime-local")
        defaultValueInput.fill("2026-06-01T09:30")
        assertThat(defaultValueInput).hasValue("2026-06-01T09:30")
        assertThat(page.locator(".dc-field-error")).hasCount(0)
        assertThat(saveButton).isEnabled()
    }
}
