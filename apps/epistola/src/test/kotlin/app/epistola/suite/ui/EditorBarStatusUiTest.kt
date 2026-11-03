// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import com.microsoft.playwright.Response
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The editor's bar says what is not published yet. It was loaded once with the page, so the first
 * change after a publish left it saying "Everything is published", with no Publish, until a reload.
 * An editor on a catalog that cannot be changed here saves nothing, and does not try.
 */
class EditorBarStatusUiTest : BasePlaywrightTest() {

    private val letters = CatalogKey.of("letters")

    @Test
    fun `a saved change shows in the bar as not published, without a reload`() {
        val tenantKey = TenantKey.of("test-editor-bar-${System.nanoTime()}")
        val template = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenantKey), letters))
        withMediator {
            TestNamespaces.createTenant(tenantKey, "Editor Bar Tenant").execute()
            CreateCatalog(tenantKey = tenantKey, id = letters, name = "Letters").execute()
            CreateDocumentTemplate(template, "Invoice", catalogVersion = null).execute().withRequiredDataExample()
            MarkCatalogReady(testCatalog(tenantKey, letters), catalogVersion = null).execute()
            ReleaseCatalogDraft(testCatalog(tenantKey, letters), "1.0.0", catalogVersion = null).execute()
        }

        gotoAndReady("/tenants/$tenantKey/catalogs/letters/templates/invoice/variants/initial/editor")
        page.waitForSelector("epistola-editor")
        assertThat(page.getByTestId("ws-pending")).hasText("Everything is published")

        val saved: Response = page.waitForResponse(
            { it.request().method() == "PUT" && it.url().endsWith("/variants/initial/content") },
        ) { page.getByTestId("palette-item-container").click() }
        Assertions.assertThat(saved.status()).isEqualTo(200)

        assertThat(page.getByTestId("ws-pending")).hasText("1 change not published")
        assertThat(page.getByTestId("catalog-bar-publish")).isVisible()
    }

    @Test
    fun `the editor on a supplied catalog says nothing is saved and sends no save`() {
        val tenantKey = TenantKey.of("test-editor-readonly-${System.nanoTime()}")
        withMediator {
            TestNamespaces.createTenant(tenantKey, "Editor Read-only Tenant").execute()
            RegisterCatalog(tenantKey = tenantKey, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenantKey, CatalogKey.of("epistola-demo"))).execute()
        }
        val writes = mutableListOf<String>()
        page.onRequest { if (it.method() != "GET" && it.url().contains("/content")) writes += it.method() + " " + it.url() }

        gotoAndReady("/tenants/$tenantKey/catalogs/epistola-demo/templates/hello-world/variants/default/editor")
        page.waitForSelector("epistola-editor")
        assertThat(page.getByTestId("editor-read-only")).hasText("Supplied by Epistola · not saved")
        // An editor that saves keeps a save state in its toolbar ("Saved" from the start); this one has none.
        assertThat(page.locator("epistola-toolbar").getByTitle("All changes saved")).hasCount(0)

        val blocks = page.getByTestId("canvas-block").count()
        page.getByTestId("palette-item-container").click()
        // The block lands on the canvas, so the editor took the change; it just keeps it to itself.
        assertThat(page.getByTestId("canvas-block")).hasCount(blocks + 1)
        assertThat(page.locator("epistola-toolbar").getByText("Retry save")).hasCount(0)
        Assertions.assertThat(writes).isEmpty()
    }
}
