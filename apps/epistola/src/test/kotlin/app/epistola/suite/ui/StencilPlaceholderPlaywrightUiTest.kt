package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencilInTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Browser-level sentinel for the canonical stencil/placeholder regression:
 * a user-authored fill override must survive a stencil edit-and-publish cycle.
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
    fun `template fill override is preserved and rendered after stencil edit-and-publish`() {
        val variantUrl = withMediator { setupOverrideSurvivingPublish() }

        page.navigate("${baseUrl()}$variantUrl")
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
     * Creates a tenant, publishes stencil v1 with a placeholder + default,
     * embeds it in a template with a user fill override, publishes stencil
     * v2 with a different default, and runs the upgrade command. Returns the
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
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), sId)).execute()

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
                    props = mapOf("stencilId" to sId.key.value, "version" to 1),
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
        UpdateDraft(variantId = variantId, templateModel = templateBody).execute()

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
        CreateStencilVersion(stencilId = sId, content = v2).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), sId)).execute()

        UpdateStencilInTemplate(
            variantId = variantId,
            stencilId = sId,
            newVersion = 2,
        ).execute()

        return "/tenants/${tenant.id}/templates/default/${template.id}/variants/${variant.id}/editor"
    }
}
