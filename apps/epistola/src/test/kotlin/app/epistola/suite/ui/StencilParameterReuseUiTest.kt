// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the reported parameter-schema loss when a stencil
 * is authored inline in one template and then reused in another template.
 */
class StencilParameterReuseUiTest : BasePlaywrightTest() {

    @Test
    fun `upgrading a parameterless stencil to a parameterized version adds parameter controls`() {
        val fixture = withMediator { createFixture() }

        openEditor(fixture.tenant, fixture.authoring)
        createStencil()
        publishStencil()
        saveTemplate()

        openEditor(fixture.tenant, fixture.consuming)
        insertParameterlessStencil()
        saveTemplate()

        openEditor(fixture.tenant, fixture.authoring)
        selectOnlyBlock()
        startEditingStencil()
        defineRecipientNameParameter()
        publishStencil()

        openEditor(fixture.tenant, fixture.consuming)
        selectOnlyBlock()
        val upgrade = page.locator("stencil-inspector button:has-text('Upgrade to v2')")
        assertThat(upgrade).isVisible()
        upgrade.click()

        assertThat(page.locator("stencil-inspector button:has-text('Configure parameters')")).isVisible()
    }

    private fun createStencil() {
        val picker = page.openDialogByTrigger(
            page.getByTestId("palette-item-stencil"),
            "dialog.stencil-picker-dialog",
        )
        picker.locator("button.create-new").click()
        picker.locator("#create-stencil-name").fill("Parameterized Greeting")
        picker.locator("#create-stencil-slug").fill("parameterized-greeting")
        picker.locator("button.create-confirm").click()

        assertThat(page.getByTestId("canvas-block")).hasCount(1)
        assertThat(page.locator("stencil-inspector button:has-text('Define parameters… (0)')")).isVisible()
    }

    private fun defineRecipientNameParameter() {
        val defineParameters = page.locator("stencil-inspector button:has-text('Define parameters')")
        val definitions = page.openDialogByTrigger(
            defineParameters,
            "dialog.stencil-picker-dialog",
        )
        definitions.locator("button:has-text('+ Add parameter')").click()
        definitions.locator("input[placeholder='recipientName']").fill("recipientName")
        definitions.locator("button.save").click()

        assertThat(page.locator("stencil-inspector button:has-text('Define parameters… (1)')")).isVisible()
    }

    private fun startEditingStencil() {
        page.locator("stencil-inspector button:has-text('Start Editing')").click()
        assertThat(page.locator("stencil-inspector button:has-text('Define parameters… (0)')")).isVisible()
    }

    private fun publishStencil() {
        page.locator("stencil-inspector button:has-text('Publish Draft')").click()

        // Publication saves the current schema before locking the local stencil.
        assertThat(page.locator("stencil-inspector button:has-text('Start Editing')")).isVisible()
    }

    private fun insertParameterlessStencil() {
        val picker = page.openDialogByTrigger(
            page.getByTestId("palette-item-stencil"),
            "dialog.stencil-picker-dialog",
        )
        val stencilCard = picker.locator(
            "#stencil-list .stencil-picker-card:has-text('Parameterized Greeting')",
        )
        assertThat(stencilCard).isVisible()
        stencilCard.click()

        val publishedVersion = picker.locator(
            "#stencil-version-list .stencil-picker-card:has-text('published')",
        )
        assertThat(publishedVersion).isVisible()
        publishedVersion.click()
        picker.locator("button.insert:not(.create-confirm)").click()

        assertThat(page.locator("dialog.stencil-picker-dialog")).hasCount(0)
        assertThat(page.getByTestId("canvas-block")).hasCount(1)
        assertThat(page.locator("stencil-inspector button:has-text('Configure parameters')")).hasCount(0)
    }

    private fun saveTemplate() {
        val save = page.locator("epistola-toolbar button.toolbar-btn:has-text('Save')")
        assertThat(save).isVisible()
        save.click()
        assertThat(page.locator("epistola-toolbar button.toolbar-btn:has-text('Saved')")).isVisible()
    }

    private fun selectOnlyBlock() {
        val block = page.getByTestId("canvas-block")
        assertThat(block).hasCount(1)
        block.click()
    }

    private fun openEditor(tenant: Tenant, target: EditorTarget) {
        gotoAndReady(
            "/tenants/${tenant.id}/templates/default/${target.template.id}" +
                "/variants/${target.variantId}/editor",
        )
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")
        page.waitForSelector("epistola-toolbar")
    }

    private fun createFixture(): Fixture {
        val tenant = CreateTenant(
            id = TenantKey.of("test-stencil-parameter-reuse-${System.nanoTime()}"),
            name = "Stencil Parameter Reuse UI Test",
        ).execute()
        val tenantId = TenantId(tenant.id)

        fun createTarget(name: String): EditorTarget {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
            val template = CreateDocumentTemplate(id = templateId, name = name).execute()
            val variant = CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                title = "$name Variant",
                description = null,
                attributes = emptyMap(),
            ).execute()!!
            return EditorTarget(template, variant.id.toString())
        }

        return Fixture(
            tenant = tenant,
            authoring = createTarget("Stencil Authoring Template"),
            consuming = createTarget("Stencil Consuming Template"),
        )
    }

    private data class Fixture(
        val tenant: Tenant,
        val authoring: EditorTarget,
        val consuming: EditorTarget,
    )

    private data class EditorTarget(
        val template: DocumentTemplate,
        val variantId: String,
    )
}
