// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.default
import com.microsoft.playwright.Browser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every tab of the editor's side panel is in sight: they wrap onto a second line rather than scroll
 * out of a strip whose arrows show only on hover, which hid the last tabs (Quality among them).
 */
class EditorSidebarTabsUiTest : BasePlaywrightTest() {

    override fun contextOptions(): Browser.NewContextOptions = Browser.NewContextOptions().setViewportSize(1360, 900)

    @Test
    fun `no tab of the side panel is cut off`() {
        val path = withMediator {
            val tenant = TestNamespaces.createTenant(TenantKey.of("sidebar-tabs-${System.nanoTime()}"), "Sidebar Tabs").execute()
            // The plugin tabs a tenant can switch on, as the demo has them: Quality last.
            listOf(KnownFeatures.QUALITY, KnownFeatures.AI_CHAT).forEach { SaveFeatureToggle(tenant.id, it, enabled = true).execute() }
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "Sidebar Tabs").execute()
            val variant = CreateVariant(VariantId(TestIdHelpers.nextVariantId(), templateId), "Default", null, emptyMap()).execute()!!
            "/tenants/${tenant.id}/catalogs/default/templates/${templateId.key.value}/variants/${variant.id}/editor"
        }

        gotoAndReady(path)
        page.waitForSelector(".sidebar-tab")

        val strip = page.locator(".sidebar-tabs").boundingBox()!!
        page.waitForSelector(".sidebar-tab[data-tab-id=quality]")
        val tabs = page.locator(".sidebar-tab").all()
        assertThat(tabs).hasSizeGreaterThanOrEqualTo(4)
        for (tab in tabs) {
            val box = tab.boundingBox()!!
            assertThat(box.x + box.width).`as`("tab '${tab.innerText()}' ends inside the strip").isLessThanOrEqualTo(strip.x + strip.width + 0.5)
        }
    }
}
