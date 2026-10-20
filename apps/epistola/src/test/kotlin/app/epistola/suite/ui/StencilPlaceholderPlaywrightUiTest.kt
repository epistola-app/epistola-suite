// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Browser-level sentinel for the canonical stencil/placeholder regression:
 * a user-authored fill override must survive an edit of the stencil it fills.
 *
 * The data path is exercised by [StencilPlaceholderIntegrationTest]; this
 * test adds the missing rung — that the rendered canvas shows the override
 * and not the (newer) stencil default after the upgrade. If the renderer or
 * the placeholder mode-switching ever regresses to drawing the stencil's
 * default text on top of the user override, this catches it.
 */
class StencilPlaceholderPlaywrightUiTest : BasePlaywrightTest() {

    /**
     * Build a ProseMirror doc with a single paragraph containing [text].
     * The text editor parses this shape; a plain string falls through to an
     * empty document, which would silently break the rendering assertions.
     */
    private fun pmDoc(text: String): Map<String, Any> = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf(
                "type" to "paragraph",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
            ),
        ),
    )

    @Test
    fun `template fill override is preserved and rendered after the stencil is edited`() {
        val variantUrl = withMediator { setupOverrideSurvivingPublish() }

        gotoAndReady(variantUrl)
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")
        page.waitForSelector(".canvas-placeholder")

        // The fill is non-empty → placeholder renders the fill slot only.
        // We expect the override text in the canvas, and the v2 default to
        // be absent (the empty-fill default-preview is not shown when filled).
        assertThat(page.locator(".canvas-placeholder--filled")).isVisible()
        assertThat(page.getByText("user override")).isVisible()
        assertThat(page.getByText("v2 default")).hasCount(0)
    }

    /**
     * The editor inserts a stencil by reference (wire v8 `stencilKey`, no version): picked from
     * the picker, the instance shows the stencil's working copy, and saving the template stores
     * only the reference. Before the editor moved to 2.0 the picker offered only versions (there
     * are none) and an inserted instance named `stencilId`, which the server refuses.
     */
    @Test
    fun `a stencil picked in the editor is inserted and saved by reference`() {
        val setup = withMediator { setupTemplateAndStencil() }

        gotoAndReady(setup.editorUrl)
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")

        page.getByTestId("palette-item-stencil").click()
        page.locator("[data-testid='stencil-picker-card'][data-stencil-key='${setup.stencilKey}']").click()
        page.getByTestId("stencil-picker-insert").click()

        // The instance shows the stencil's working copy.
        assertThat(page.getByText("v1 default")).isVisible()

        page.keyboard().press("ControlOrMeta+s")
        assertThat(page.locator(".toolbar-btn.saved")).isVisible()

        val stored = withMediator { GetVariantContent(setup.variantId).query() }!!
        val instance = stored.nodes.values.single { it.type == "stencil" }
        Assertions.assertThat(instance.props).containsEntry("stencilKey", setup.stencilKey)
        Assertions.assertThat(instance.props).doesNotContainKeys("stencilId", "version")
        // Nothing of the stencil's own content is stored with the template.
        Assertions.assertThat(stored.nodes.values.map { it.type }).doesNotContain("text")
    }

    private data class TemplateAndStencil(val editorUrl: String, val variantId: VariantId, val stencilKey: String)

    private fun setupTemplateAndStencil(): TemplateAndStencil {
        val tenant = CreateTenant(
            id = TenantKey.of("ui-stencil-ins-${System.nanoTime()}"),
            name = "Stencil Insert UI Test",
        ).execute()
        val tenantId = TenantId(tenant.id)
        val sId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = sId, name = "Letter Body", content = stencilContent("v1 default")).execute()

        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Letter").execute()
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()).execute()!!
        return TemplateAndStencil(
            editorUrl = "/tenants/${tenant.id}/catalogs/default/templates/${template.id}/variants/${variant.id}/editor",
            variantId = variantId,
            stencilKey = sId.key.value,
        )
    }

    /** A stencil with a placeholder "body" whose default is [defaultText]. */
    private fun stencilContent(defaultText: String) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph" to Node(id = "ph", type = "placeholder", slots = listOf("ph-default", "ph-fill"), props = mapOf("name" to "body", "kind" to "block")),
            "default-text" to Node(id = "default-text", type = "text", slots = emptyList(), props = mapOf("content" to pmDoc(defaultText))),
        ),
        slots = mapOf(
            "root-slot" to Slot("root-slot", "root", "children", listOf("ph")),
            "ph-default" to Slot("ph-default", "ph", "default", listOf("default-text")),
            "ph-fill" to Slot("ph-fill", "ph", "fill", emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )

    /**
     * Creates a tenant and a stencil with a placeholder + default, embeds it
     * in a template with a user fill override, then edits the stencil to a
     * different default. Returns the
     * relative URL to the variant editor (so the test can navigate to it).
     */
    private fun setupOverrideSurvivingPublish(): String {
        val tenant = CreateTenant(
            id = TenantKey.of("ui-stencil-${System.nanoTime()}"),
            name = "Stencil UI Test",
        ).execute()
        val tenantId = TenantId(tenant.id)
        val sId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))

        // v1: placeholder "body" with default "v1 default".
        val v1 = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "ph" to Node(
                    id = "ph",
                    type = "placeholder",
                    slots = listOf("ph-default", "ph-fill"),
                    props = mapOf("name" to "body", "kind" to "block"),
                ),
                "v1-default" to Node(
                    id = "v1-default",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to pmDoc("v1 default")),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("ph")),
                "ph-default" to Slot("ph-default", "ph", "default", listOf("v1-default")),
                "ph-fill" to Slot("ph-fill", "ph", "fill", emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )
        CreateStencil(id = sId, name = "Letter Body", content = v1).execute()

        // Template + variant; embed the stencil with a user fill override.
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Letter").execute()
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = CreateVariant(
            id = variantId,
            title = "Default",
            description = null,
            attributes = emptyMap(),
        ).execute()!!

        val templateBody = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "stencil-instance" to Node(
                    id = "stencil-instance",
                    type = "stencil",
                    slots = listOf("stencil-children"),
                    props = mapOf("stencilKey" to sId.key.value, "version" to 1),
                ),
                "embedded-ph" to Node(
                    id = "embedded-ph",
                    type = "placeholder",
                    slots = listOf("embedded-ph-default", "embedded-ph-fill"),
                    props = mapOf("name" to "body", "kind" to "block"),
                ),
                "embedded-default" to Node(
                    id = "embedded-default",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to pmDoc("v1 default")),
                ),
                "user-override" to Node(
                    id = "user-override",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to pmDoc("user override")),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil-instance")),
                "stencil-children" to Slot(
                    "stencil-children",
                    "stencil-instance",
                    "children",
                    listOf("embedded-ph"),
                ),
                "embedded-ph-default" to Slot(
                    "embedded-ph-default",
                    "embedded-ph",
                    "default",
                    listOf("embedded-default"),
                ),
                "embedded-ph-fill" to Slot(
                    "embedded-ph-fill",
                    "embedded-ph",
                    "fill",
                    listOf("user-override"),
                ),
            ),
            themeRef = ThemeRef.Inherit,
        )
        UpdateVariantContent(variantId = variantId, templateModel = templateBody).execute()

        // v2: same placeholder name, different default.
        val v2 = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "ph" to Node(
                    id = "ph",
                    type = "placeholder",
                    slots = listOf("ph-default", "ph-fill"),
                    props = mapOf("name" to "body", "kind" to "block"),
                ),
                "v2-default" to Node(
                    id = "v2-default",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to pmDoc("v2 default")),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("ph")),
                "ph-default" to Slot("ph-default", "ph", "default", listOf("v2-default")),
                "ph-fill" to Slot("ph-fill", "ph", "fill", emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )
        // The stencil is used by reference: editing its working copy reaches the template as is.
        UpdateStencil(id = sId, content = v2).execute()

        return "/tenants/${tenant.id}/catalogs/default/templates/${template.id}/variants/${variant.id}/editor"
    }
}
