package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
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
 * Browser coverage for the walkthrough — the one place driver.js can be exercised for
 * real (its popover DOM and animation don't reproduce under happy-dom). Chapters are
 * passive narration: the user reads each step and advances with the Next button. This
 * asserts the Editing chapter opens, spotlights a real block, and steps content → style
 * → delete across Next clicks.
 */
class EditorWalkthroughUiTest : BasePlaywrightTest() {

    @Test
    fun `editing chapter narrates content, style and delete across Next clicks`() {
        val (tenant, template, variantId) = withMediator { createWalkthroughTenant() }
        openEditorPage(tenant, template, variantId)

        // Give the chapter a block to point at, then DESELECT it. This is the real
        // scenario: the chapter's `setup` must select a block itself so the spotlights
        // have a target — a block auto-selected by the add would mask that.
        page.getByTestId("palette-item-text").click()
        assertThat(page.getByTestId("canvas-block")).hasCount(1)
        page.keyboard().press("Escape")
        assertThat(page.locator(".canvas-block.selected")).hasCount(0)

        // Start the "Editing a block" chapter deterministically (not via first-run logic).
        page.getByTestId("walkthrough-guide-trigger").click()
        page.getByTestId("walkthrough-chapter-editing").click()

        val title = page.locator(".driver-popover-title")
        val next = page.locator(".driver-popover-next-btn")

        // setup() selects the block, so step 1 spotlights it and narrates editing.
        assertThat(title).hasText("Edit its content")
        next.click()
        assertThat(title).hasText("Style it")
        next.click()
        assertThat(title).hasText("Remove a block")
    }

    @Test
    fun `finishing building drops a starter block and chains into the editing chapter`() {
        val (tenant, template, variantId) = withMediator { createWalkthroughTenant() }
        openEditorPage(tenant, template, variantId)

        // Empty canvas: the block-centric chapters have nothing to work with yet.
        assertThat(page.getByTestId("canvas-block")).hasCount(0)

        page.getByTestId("walkthrough-guide-trigger").click()
        page.getByTestId("walkthrough-chapter-building").click()

        val title = page.locator(".driver-popover-title")
        val next = page.locator(".driver-popover-next-btn")

        // Read through building's three narration steps.
        assertThat(title).hasText("The block palette")
        next.click()
        assertThat(title).hasText("Adding a block")
        next.click()
        assertThat(title).hasText("The structure")

        // The done button offers to level up into the next chapter; clicking it drops a
        // starter block (onComplete) and chains straight into Editing.
        assertThat(next).hasText("Next: Editing a block →")
        next.click()
        assertThat(page.getByTestId("canvas-block")).hasCount(1)
        assertThat(title).hasText("Edit its content")
    }

    private fun openEditorPage(tenant: Tenant, template: DocumentTemplate, variantId: String) {
        // Suppress the first-run coach-mark so the chapter start is deterministic.
        page.addInitScript(
            "try { localStorage.setItem('ep:editor-walkthrough:intro-seen', 'true'); } catch (e) {}",
        )
        val path = "/tenants/${tenant.id}/templates/default/${template.id}/variants/$variantId/editor"
        gotoAndReady(path)
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")
        page.waitForSelector("epistola-toolbar")
    }

    private fun createWalkthroughTenant(): Triple<Tenant, DocumentTemplate, String> {
        val tenantKey = TenantKey.of("test-editor-walkthrough-${System.nanoTime()}")
        val tenant = CreateTenant(id = tenantKey, name = "Walkthrough UI Test Tenant").execute()
        val tenantId = TenantId(tenant.id)

        // Turn the alpha walkthrough feature on for this tenant (off by default).
        SaveFeatureToggle(tenantKey, KnownFeatures.EDITOR_WALKTHROUGH, enabled = true).execute()

        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Walkthrough Template").execute()

        val variant = CreateVariant(
            id = VariantId(TestIdHelpers.nextVariantId(), templateId),
            title = "Walkthrough Variant",
            description = null,
            attributes = emptyMap(),
        ).execute()

        return Triple(tenant, template, variant!!.id.toString())
    }
}
