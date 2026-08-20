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
import tools.jackson.databind.node.JsonNodeFactory

class DataContractSaveBarUiTest : BasePlaywrightTest() {

    @Test
    fun `sticky action bar saves a draft and remains in edit mode after refresh`() {
        val (tenant, template) = withMediator {
            val tenant = CreateTenant(
                id = TenantKey.of("contract-save-ui-${System.nanoTime()}"),
                name = "Contract Save Bar UI Tenant",
            ).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Contract Save Bar UI Template",
            ).execute()
            CreateContractVersion(templateId = templateId).execute()
            UpdateContractVersion(
                templateId = templateId,
                dataExamples = listOf(
                    DataExample(
                        id = "save-bar-example",
                        name = "Original example",
                        data = JsonNodeFactory.instance.objectNode(),
                    ),
                ),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/data-contract?edit=true")

        val statusBar = page.locator("#contract-status-bar")
        val saveButton = page.locator("#contract-save-controls .dc-save-btn")
        assertThat(statusBar).isVisible()
        assertThat(saveButton).hasText("Save draft")
        assertThat(saveButton).isDisabled()
        org.assertj.core.api.Assertions.assertThat(
            statusBar.evaluate(
                "element => getComputedStyle(element).position + ' ' + getComputedStyle(element).top",
            ),
        ).isEqualTo("sticky 48px")

        val exampleName = page.locator("#example-name-input")
        exampleName.fill("Updated example")
        exampleName.press("Tab")
        assertThat(saveButton).isEnabled()
        assertThat(statusBar).containsText("Unsaved draft changes")
        assertThat(statusBar).containsText("Examples")

        saveButton.click()
        page.htmxSettle()

        assertThat(page.locator("#contract-status-bar .dc-status-success")).hasText("Draft saved")
        assertThat(page.locator("#contract-status-bar #contract-save-controls")).hasCount(1)
        assertThat(page.locator("#contract-status-bar a[href$='/data-contract']")).isVisible()
        assertThat(page.locator("#contract-status-bar button[hx-post*='/contract/publish']")).hasCount(0)
    }
}
