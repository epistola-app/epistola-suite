// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.default
import com.microsoft.playwright.Dialog
import com.microsoft.playwright.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The editor saves against the variant's revision it opened at. When someone else saved the
 * variant since, the save is refused (412) and the editor asks: replace their version with this
 * one, or reload to see theirs.
 */
class EditorConflictUiTest : BasePlaywrightTest() {

    private fun openEditorThenChangeElsewhere(): VariantId {
        val tenantKey = TenantKey.of("test-editor-conflict-${System.nanoTime()}")
        val template = TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenantKey)))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            TestNamespaces.createTenant(tenantKey, "Editor Conflict").execute()
            CreateDocumentTemplate(template, "Invoice").execute()
        }
        gotoAndReady("/tenants/$tenantKey/catalogs/default/templates/invoice/variants/${variant.key}/editor")
        page.waitForSelector("epistola-editor")
        // Someone else saves the variant while it is open here: its revision moves on.
        withMediator { UpdateVariant(variantId = variant, title = "Renamed elsewhere", attributes = emptyMap()).execute() }
        return variant
    }

    private fun containers(variant: VariantId) = withMediator { GetVariantContent(variant).query()!! }.nodes.values.count { it.type == "container" }

    /** What the editor asked; read once the request that follows the answer arrived. */
    private var asked = ""

    /** Adds a block, which saves; the save is refused and the editor asks, answered with [accept]. */
    private fun editAndAnswer(variant: VariantId, accept: Boolean) {
        page.onceDialog { dialog: Dialog ->
            asked = dialog.message()
            if (accept) dialog.accept() else dialog.dismiss()
        }
        page.waitForResponse({ it.request().method() == "PUT" && it.url().endsWith("/variants/${variant.key}/content") && it.status() == 412 }) {
            page.getByTestId("palette-item-container").click()
        }
    }

    @Test
    fun `keeping this version replaces theirs`() {
        val variant = openEditorThenChangeElsewhere()

        val overwrite: Response = page.waitForResponse({ it.request().method() == "PUT" && it.url().endsWith("/content") && it.status() == 200 }) {
            editAndAnswer(variant, accept = true)
        }

        assertThat(asked).contains("Someone else saved this variant")
        assertThat(overwrite.status()).isEqualTo(200)
        assertThat(containers(variant)).isEqualTo(1)
    }

    @Test
    fun `reloading keeps theirs and loses the edit here`() {
        val variant = openEditorThenChangeElsewhere()

        // Cancel reloads the page: the editor is fetched again.
        page.waitForResponse({ it.request().method() == "GET" && it.url().endsWith("/variants/${variant.key}/editor") }) {
            editAndAnswer(variant, accept = false)
        }
        assertThat(asked).contains("Someone else saved this variant")
        page.waitForSelector("epistola-editor")

        assertThat(containers(variant)).isZero()
        assertThat(withMediator { GetVariant(variant).query() }!!.title).isEqualTo("Renamed elsewhere")
    }
}
