// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CreateCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.withRequiredDataExample
import com.microsoft.playwright.Response
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The template editor opened in one draft of a catalog with several (contract ADR 0002) saves into
 * that draft: its own requests carry the catalog segment (`letters@1.0.1`) from the editor config.
 */
class EditorDraftSaveUiTest : BasePlaywrightTest() {

    private val letters = CatalogKey.of("letters")

    @Test
    fun `the editor opened in a draft saves the variant into that draft`() {
        val tenantKey = TenantKey.of("test-editor-draft-${System.nanoTime()}")
        val template = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenantKey)))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            CreateTenant(id = tenantKey, name = "Editor Draft Tenant").execute()
            CreateCatalog(tenantKey = tenantKey, id = letters, name = "Letters").execute()
            CreateDocumentTemplate(template, "Invoice").execute().withRequiredDataExample()
            MarkCatalogReady(tenantKey, letters).execute()
            ReleaseCatalogVersion(tenantKey, letters, "1.0.0").execute()
            // 1.0.1 carries on from the release; 2.0.0 is the higher draft, the current one.
            CreateCatalogVersion(tenantKey, letters, "2.0.0").execute()
        }

        gotoAndReady("/tenants/$tenantKey/templates/letters@1.0.1/invoice/variants/${variant.key}/editor")
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")
        // Side work is named in the editor's bar by what it fixes, so nobody takes it for the next version.
        assertThat(page.getByTestId("editor-crumb-version")).hasText("Fix of v1.0.0")

        // Adding a block marks the document dirty; its autosave is the PUT asserted on.
        val saved: Response = page.waitForResponse(
            { it.request().method() == "PUT" && it.url().endsWith("/variants/${variant.key}/content") },
        ) { page.getByTestId("palette-item-container").click() }

        Assertions.assertThat(saved.status()).isEqualTo(200)
        Assertions.assertThat(saved.url()).contains("/templates/letters%401.0.1/invoice/")
        fun containers(version: String) = withMediator {
            GetVariantContent(variant, catalogVersion = CatalogVersionRef(tenantKey, letters, version)).query()!!
        }.nodes.values.count { it.type == "container" }
        Assertions.assertThat(containers("1.0.1")).isEqualTo(1)
        Assertions.assertThat(containers("2.0.0")).isZero()
    }
}
