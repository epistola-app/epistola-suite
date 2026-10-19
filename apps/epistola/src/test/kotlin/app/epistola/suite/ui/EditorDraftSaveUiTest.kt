// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
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
 * The template editor opened in parallel work (contract ADR 0002, amendment 2) saves into that
 * work: its own requests carry the catalog segment (`letters@<draft id>`) from the editor config.
 */
class EditorDraftSaveUiTest : BasePlaywrightTest() {

    private val letters = CatalogKey.of("letters")

    @Test
    fun `the editor opened in parallel work saves the variant into that work`() {
        val tenantKey = TenantKey.of("test-editor-draft-${System.nanoTime()}")
        val template = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenantKey)))
        val variant = VariantId(VariantKey.INITIAL, template)
        val work = withMediator {
            CreateTenant(id = tenantKey, name = "Editor Draft Tenant").execute()
            CreateCatalog(tenantKey = tenantKey, id = letters, name = "Letters").execute()
            CreateDocumentTemplate(template, "Invoice").execute().withRequiredDataExample()
            MarkCatalogReady(tenantKey, letters).execute()
            ReleaseCatalogVersion(tenantKey, letters, "1.0.0").execute()
            requireNotNull(CreateCatalogDraft(tenantKey, letters, "Redesign").execute().versionId)
        }

        gotoAndReady("/tenants/$tenantKey/catalogs/letters@$work/templates/invoice/variants/${variant.key}/editor")
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")
        // Parallel work is named in the editor's bar, so nobody takes it for the next version.
        assertThat(page.getByTestId("editor-crumb-version")).hasText("Redesign")

        // Adding a block marks the document dirty; its autosave is the PUT asserted on.
        val saved: Response = page.waitForResponse(
            { it.request().method() == "PUT" && it.url().endsWith("/variants/${variant.key}/content") },
        ) { page.getByTestId("palette-item-container").click() }

        Assertions.assertThat(saved.status()).isEqualTo(200)
        Assertions.assertThat(saved.url()).contains("/catalogs/letters%40$work/templates/invoice/")
        fun containers(draft: DraftRef) = withMediator {
            GetVariantContent(variant, catalogVersion = CatalogVersionRef(tenantKey, letters, draft)).query()!!
        }.nodes.values.count { it.type == "container" }
        Assertions.assertThat(containers(DraftRef.Id(work))).isEqualTo(1)
        Assertions.assertThat(containers(DraftRef.Next)).isZero()
    }
}
