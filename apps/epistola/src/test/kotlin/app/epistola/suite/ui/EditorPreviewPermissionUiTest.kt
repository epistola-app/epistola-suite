// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import com.microsoft.playwright.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A content viewer opens the editor read-only and previews the template it shows. Previewing a model
 * of one's own needs permission to edit the template, so a read-only editor previews the saved one.
 */
class EditorPreviewPermissionUiTest : BasePlaywrightTest() {

    private val letters = CatalogKey.of("letters")

    @Test
    fun `a content viewer previews the template in a read-only editor`() {
        val tenantKey = TenantKey.of("test-editor-preview-viewer-${System.nanoTime()}")
        val template = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenantKey), letters))
        withMediator {
            TestNamespaces.createTenant(tenantKey, "Editor Preview Viewer Tenant").execute()
            CreateCatalog(tenantKey = tenantKey, id = letters, name = "Letters").execute()
            CreateDocumentTemplate(template, "Invoice", catalogVersion = null).execute().withRequiredDataExample()
        }
        page.setExtraHTTPHeaders(mapOf(TEST_TENANT_ROLES_HEADER to "CONTENT_VIEWER"))

        gotoAndReady("/tenants/$tenantKey/catalogs/letters/templates/invoice/variants/initial/editor")
        page.waitForSelector("epistola-editor")
        val preview: Response = page.waitForResponse(
            { it.request().method() == "POST" && it.url().endsWith("/variants/initial/preview") },
        ) { page.locator("epistola-toolbar").getByTitle("Show preview").click() }

        assertThat(preview.status()).isEqualTo(200)
        assertThat(preview.headerValue("content-type")).startsWith("application/pdf")
        assertThat(preview.request().postData()).contains("\"templateModel\":null")
    }
}
