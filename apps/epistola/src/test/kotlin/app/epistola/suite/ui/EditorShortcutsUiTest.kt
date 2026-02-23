package app.epistola.suite.ui

import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

class EditorShortcutsUiTest : BasePlaywrightTest() {

    companion object {
        // Leader idle timeout is 1600ms in EpistolaEditor; we wait slightly longer to avoid scheduler jitter.
        private const val LEADER_IDLE_HIDE_WAIT_MS = 1900.0

        // Leader result timeout is 700ms and clear timeout is 180ms in EpistolaEditor; add buffer for timing variance.
        private const val LEADER_RESULT_HIDE_WAIT_MS = 950.0
        private const val LEADER_MESSAGE_CLEAR_WAIT_MS = 260.0
    }

    @Test
    fun `escape clears selected block`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.getByTestId("palette-item-container").click()
        val blocks = page.getByTestId("canvas-block")
        assertThat(blocks).hasCount(1)

        blocks.first().click()
        assertThat(page.locator(".canvas-block.selected")).hasCount(1)

        page.keyboard().press("Escape")
        assertThat(page.locator(".canvas-block.selected")).hasCount(0)
    }

    @Test
    fun `leader question mark opens shortcuts and escape closes it`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.keyboard().press("Control+Space")
        page.keyboard().press("Shift+/")

        val popover = page.getByTestId("shortcuts-popover")
        assertThat(popover).isVisible()
        assertThat(popover).containsText("Keyboard Shortcuts")

        page.keyboard().press("Escape")
        assertThat(popover).hasCount(0)
    }

    @Test
    fun `leader activation shows waiting hint`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.keyboard().press("Control+Space")

        val leaderHint = page.getByTestId("leader-hint")
        assertThat(leaderHint).isVisible()
        assertThat(leaderHint).containsText("Waiting:")
    }

    @Test
    fun `leader invalid command shows error and keeps editor unchanged`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.getByTestId("palette-item-container").click()
        val initialCount = page.getByTestId("canvas-block").count()

        page.keyboard().press("Control+Space")
        page.keyboard().press("x")

        val leaderHint = page.getByTestId("leader-hint")
        assertThat(leaderHint).isVisible()
        assertThat(page.getByTestId("leader-message")).containsText("Unknown leader command")

        assertThat(page.getByTestId("shortcuts-popover")).hasCount(0)
        assertThat(page.getByTestId("insert-dialog")).hasCount(0)
        assertThat(page.getByTestId("canvas-block")).hasCount(initialCount)
    }

    @Test
    fun `leader waiting hint auto hides after timeout`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        val leaderHint = page.getByTestId("leader-hint")
        val leaderMessage = page.getByTestId("leader-message")

        page.keyboard().press("Control+Space")
        assertThat(leaderHint).isVisible()
        assertThat(leaderMessage).containsText("Waiting:")

        page.waitForTimeout(LEADER_IDLE_HIDE_WAIT_MS)
        assertThat(leaderHint).isHidden()
        assertThat(leaderMessage).hasText("")
    }

    @Test
    fun `leader success hint auto hides and clears after result timeout`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        val leaderHint = page.getByTestId("leader-hint")
        val leaderMessage = page.getByTestId("leader-message")

        page.keyboard().press("Control+Space")
        page.keyboard().press("Shift+/")

        assertThat(page.getByTestId("shortcuts-popover")).isVisible()
        assertThat(leaderHint).isVisible()
        assertThat(leaderMessage).containsText("Opened shortcuts help")

        page.waitForTimeout(LEADER_RESULT_HIDE_WAIT_MS)
        assertThat(leaderHint).isHidden()

        page.waitForTimeout(LEADER_MESSAGE_CLEAR_WAIT_MS)
        assertThat(leaderMessage).hasText("")
    }

    @Test
    fun `leader error hint auto hides and clears after result timeout`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        val leaderHint = page.getByTestId("leader-hint")
        val leaderMessage = page.getByTestId("leader-message")

        page.keyboard().press("Control+Space")
        page.keyboard().press("x")

        assertThat(leaderHint).isVisible()
        assertThat(leaderMessage).containsText("Unknown leader command")
        assertThat(page.getByTestId("shortcuts-popover")).hasCount(0)
        assertThat(page.getByTestId("insert-dialog")).hasCount(0)

        page.waitForTimeout(LEADER_RESULT_HIDE_WAIT_MS)
        assertThat(leaderHint).isHidden()

        page.waitForTimeout(LEADER_MESSAGE_CLEAR_WAIT_MS)
        assertThat(leaderMessage).hasText("")
    }

    private fun openEditorPage(tenant: Tenant, template: DocumentTemplate, variantId: String) {
        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}/variants/$variantId/editor")
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")
        page.waitForSelector("epistola-toolbar")
    }

    private fun createTenantTemplateAndVariant(): Triple<Tenant, DocumentTemplate, String> {
        val tenantId = TenantId.of("test-editor-shortcuts-${System.nanoTime()}")
        val tenant = CreateTenant(id = tenantId, name = "UI Test Tenant").execute()
        val template = CreateDocumentTemplate(
            id = TestIdHelpers.nextTemplateId(),
            tenantId = tenant.id,
            name = "Editor Shortcut Template",
        ).execute()

        val variant = CreateVariant(
            id = TestIdHelpers.nextVariantId(),
            tenantId = tenant.id,
            templateId = template.id,
            title = "Shortcut Variant",
            description = null,
            attributes = emptyMap(),
        ).execute()

        UpdateDocumentTemplate(
            tenantId = tenant.id,
            id = template.id,
            dataExamples = listOf(
                DataExample(
                    id = "example-1",
                    name = "Example 1",
                    data = JsonNodeFactory.instance.objectNode().put("name", "Test"),
                ),
            ),
        ).execute()

        return Triple(tenant, template, variant!!.id.toString())
    }
}
