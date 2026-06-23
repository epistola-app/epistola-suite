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
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class EditorShortcutsUiTest : BasePlaywrightTest() {

    companion object {
        // The editor reads ?leaderTiming= (issue #418, Instance C seam) and shrinks
        // or stretches the leader-hint TTLs accordingly. We use this to make
        // timing-dependent behavior *deterministic* instead of racing a wall clock:
        //
        //  - STICKY: TTLs ~10min — the hint never auto-hides during a test, so
        //    web-first assertions on its visible/content state are race-free.
        //  - MODERATE: a several-second show window (reliably catchable by
        //    web-first polling on the hardened serial CI) followed by an
        //    auto-hide well within the 15s assertion budget.
        //  - FAST: TTLs ~50ms — the hide happens immediately, so the end state
        //    (hidden + cleared) is what web-first observes.
        private const val STICKY = """{"idleHideMs":600000,"resultHideMs":600000,"messageClearMs":600000}"""
        private const val MODERATE = """{"idleHideMs":2000,"resultHideMs":2000,"messageClearMs":300}"""
        private const val FAST = """{"idleHideMs":50,"resultHideMs":50,"messageClearMs":30}"""
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
    fun `leader slash opens shortcuts and escape closes it`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.keyboard().press("Control+Period")
        page.keyboard().press("/")

        val popover = page.getByTestId("shortcuts-popover")
        assertThat(popover).isVisible()
        assertThat(popover).containsText("Keyboard Shortcuts")

        page.keyboard().press("Escape")
        assertThat(popover).hasCount(0)
    }

    @Test
    fun `leader e opens json inspector on the data view`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.keyboard().press("Control+Period")
        page.keyboard().press("e")

        val popover = page.getByTestId("inspector-popover")
        assertThat(popover).isVisible()
        // Opened on the Data view: the data tab is selected and the example shows.
        assertThat(page.getByTestId("inspector-tab-data")).hasAttribute("aria-selected", "true")
        assertThat(popover).containsText("Example 1")
    }

    @Test
    fun `leader j opens json inspector on the template view`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.keyboard().press("Control+Period")
        page.keyboard().press("j")

        val popover = page.getByTestId("inspector-popover")
        assertThat(popover).isVisible()
        assertThat(page.getByTestId("inspector-tab-template")).hasAttribute("aria-selected", "true")
        assertThat(popover).containsText("Effective template document")
    }

    @Test
    fun `leader activation shows waiting hint`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        // STICKY: the hint must not auto-hide while we assert it.
        openEditorPage(tenant, template, variantId, STICKY)

        page.keyboard().press("Control+Period")

        val leaderHint = page.getByTestId("leader-hint")
        assertThat(leaderHint).isVisible()
        assertThat(leaderHint).containsText("Waiting:")
    }

    @Test
    fun `leader invalid command shows error and keeps editor unchanged`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        // STICKY: assert the error hint/message without racing its TTL.
        openEditorPage(tenant, template, variantId, STICKY)

        page.getByTestId("palette-item-container").click()
        val initialCount = page.getByTestId("canvas-block").count()

        page.keyboard().press("Control+Period")
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
        // MODERATE: a multi-second show window then a deterministic auto-hide.
        openEditorPage(tenant, template, variantId, MODERATE)

        val leaderHint = page.getByTestId("leader-hint")
        val leaderMessage = page.getByTestId("leader-message")

        page.keyboard().press("Control+Period")
        assertThat(leaderHint).isVisible()
        assertThat(leaderMessage).containsText("Waiting:")

        // Web-first: polls up to 15s for the auto-hide (fires at ~2s). No
        // wall-clock sleep — the assertion *is* the wait.
        assertThat(leaderHint).isHidden()
        assertThat(leaderMessage).hasText("")
    }

    @Test
    fun `leader success hint auto hides and clears after result timeout`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        // FAST: the success hint hides immediately. The shortcuts popover is a
        // *sticky* side effect, so it (not the transient hint) proves the
        // command fired; the hint's job here is only to end hidden + cleared.
        openEditorPage(tenant, template, variantId, FAST)

        page.keyboard().press("Control+Period")
        page.keyboard().press("/")

        // The command ran successfully (sticky proof, no TTL race).
        assertThat(page.getByTestId("shortcuts-popover")).isVisible()

        // The transient success hint ends hidden and cleared (web-first).
        assertThat(page.getByTestId("leader-hint")).isHidden()
        assertThat(page.getByTestId("leader-message")).hasText("")
    }

    @Test
    fun `leader error hint auto hides and clears after result timeout`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        // MODERATE: catch the error hint/message, then the deterministic hide.
        openEditorPage(tenant, template, variantId, MODERATE)

        val leaderHint = page.getByTestId("leader-hint")
        val leaderMessage = page.getByTestId("leader-message")

        page.keyboard().press("Control+Period")
        page.keyboard().press("x")

        assertThat(leaderHint).isVisible()
        assertThat(leaderMessage).containsText("Unknown leader command")
        assertThat(page.getByTestId("shortcuts-popover")).hasCount(0)
        assertThat(page.getByTestId("insert-dialog")).hasCount(0)

        // Web-first auto-hide + clear; no wall-clock sleep.
        assertThat(leaderHint).isHidden()
        assertThat(leaderMessage).hasText("")
    }

    @Test
    fun `insert dialog focuses search after choosing placement and quick-select works`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.getByTestId("palette-item-container").click()
        val blocks = page.getByTestId("canvas-block")
        assertThat(blocks).hasCount(1)

        blocks.first().click()
        page.keyboard().press("Control+Period")
        page.keyboard().press("a")

        val insertDialog = page.getByTestId("insert-dialog")
        assertThat(insertDialog).isVisible()

        page.keyboard().press("a")

        val searchInput = page.locator(".insert-dialog-search")
        assertThat(searchInput).isVisible()
        assertThat(searchInput).isFocused()

        page.keyboard().press("1")

        assertThat(page.getByTestId("insert-dialog")).hasCount(0)
        assertThat(blocks).hasCount(2)
    }

    @Test
    fun `insert dialog allows arrow navigation while search input is focused`() {
        val (tenant, template, variantId) = withMediator { createTenantTemplateAndVariant() }
        openEditorPage(tenant, template, variantId)

        page.getByTestId("palette-item-container").click()
        val blocks = page.getByTestId("canvas-block")
        assertThat(blocks).hasCount(1)

        blocks.first().click()
        page.keyboard().press("Control+Period")
        page.keyboard().press("a")
        page.keyboard().press("a")

        val searchInput = page.locator(".insert-dialog-search")
        assertThat(searchInput).isVisible()
        assertThat(searchInput).isFocused()

        page.keyboard().press("ArrowDown")
        page.keyboard().press("Enter")

        assertThat(page.getByTestId("insert-dialog")).hasCount(0)
        assertThat(blocks).hasCount(2)
    }

    /**
     * Navigates to the variant editor. [leaderTiming] (when non-null) is a JSON
     * `{idleHideMs,resultHideMs,messageClearMs}` override forwarded to the
     * editor via the `?leaderTiming=` test seam (issue #418, Instance C).
     */
    private fun openEditorPage(
        tenant: Tenant,
        template: DocumentTemplate,
        variantId: String,
        leaderTiming: String? = null,
    ) {
        val base = "/tenants/${tenant.id}/templates/default/${template.id}/variants/$variantId/editor"
        val path = if (leaderTiming == null) {
            base
        } else {
            "$base?leaderTiming=${URLEncoder.encode(leaderTiming, StandardCharsets.UTF_8)}"
        }
        gotoAndReady(path)
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")
        page.waitForSelector("epistola-toolbar")
    }

    private fun createTenantTemplateAndVariant(): Triple<Tenant, DocumentTemplate, String> {
        val tenantKey = TenantKey.of("test-editor-shortcuts-${System.nanoTime()}")
        val tenant = CreateTenant(id = tenantKey, name = "UI Test Tenant").execute()
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(
            id = templateId,
            name = "Editor Shortcut Template",
        ).execute()

        val variant = CreateVariant(
            id = VariantId(TestIdHelpers.nextVariantId(), templateId),
            title = "Shortcut Variant",
            description = null,
            attributes = emptyMap(),
        ).execute()

        // Add data examples via contract version command
        val examples = listOf(
            DataExample(
                id = "example-1",
                name = "Example 1",
                data = JsonNodeFactory.instance.objectNode().put("name", "Test"),
            ),
        )
        app.epistola.suite.templates.contracts.commands.UpdateContractVersion(
            templateId = templateId,
            dataExamples = examples,
        ).execute()

        return Triple(tenant, template, variant!!.id.toString())
    }
}
