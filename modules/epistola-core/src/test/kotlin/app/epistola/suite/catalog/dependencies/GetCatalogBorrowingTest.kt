// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a catalog version takes from other catalogs, made explicit: what it uses of each, whether it
 * declares it, which release that renders from, what that release offers — and declaring what it
 * uses so that publishing does not refuse it.
 */
class GetCatalogBorrowingTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")
    private val forms = CatalogKey.of("forms")

    /** `shared` (brand theme, footer stencil) published as 1.0.0, an empty `forms`, and `letters`. */
    private fun setUp(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateCatalog(tenant, forms, "Forms").execute()
        val catalog = testCatalog(TenantId(tenant), shared)
        CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
        CreateStencil(StencilId(StencilKey.of("footer"), catalog), "Footer").execute()
        MarkCatalogReady(testCatalog(tenant, shared)).execute()
        ReleaseCatalogDraft(testCatalog(tenant, shared), "1.0.0").execute()
        tenant
    }

    private fun invoiceOnSharedTheme(tenant: TenantKey, version: CatalogReleaseRef? = null) = withMediator {
        val templateId = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))
        if (version == null) CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(templateId.tenantKey, shared), catalogVersion = version).execute()
    }

    @Test
    fun `using another catalog's theme declares that catalog, naming its next version, which this renders from`() {
        val tenant = setUp("borrow-declared-on-use")
        invoiceOnSharedTheme(tenant)

        val borrowing = withMediator { GetCatalogBorrowing(testCatalog(tenant, letters)).query() }

        val used = borrowing.catalogs.single()
        assertThat(used.catalogKey).isEqualTo("shared")
        assertThat(used.declared).isTrue()
        assertThat(used.release).`as`("no pin: it names a draft").isNull()
        assertThat(used.draftName).isEqualTo("Next release")
        assertThat(used.uses.map { Triple(it.type, it.key, it.name) }).containsExactly(Triple("theme", "brand", "Brand"))
        assertThat(used.publishAlong).`as`("its next version has nothing unpublished").isFalse()
        assertThat(borrowing.undeclared).isEmpty()
        assertThat(borrowing.others.map { it.catalogKey }).`as`("the tenant's other catalogs, which it could use").contains("forms")
    }

    @Test
    fun `a used catalog's next version with unpublished changes is published along, numbered from them`() {
        val tenant = setUp("borrow-along")
        invoiceOnSharedTheme(tenant)
        withMediator { UpdateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), shared)), documentStyles = mapOf("color" to "#ff0000")).execute() }

        val used = withMediator { GetCatalogBorrowing(testCatalog(tenant, letters)).query() }.catalogs.single()

        assertThat(used.publishAlong).isTrue()
        assertThat(used.nextNumber).isEqualTo("1.0.1")
        assertThat(used.unpublishedUsed.map { it.key }).containsExactly("brand")
    }

    @Test
    fun `a catalog used that was never published is published along as its first version`() {
        val tenant = setUp("borrow-never-published")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("plain"), testCatalog(TenantId(tenant), forms)), "Plain").execute() }
        withMediator {
            val templateId = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("plain"), themeCatalog = testCatalog(templateId.tenantKey, forms)).execute()
        }

        val used = withMediator { GetCatalogBorrowing(testCatalog(tenant, letters)).query() }.catalogs.single()

        assertThat(used.catalogKey).isEqualTo("forms")
        assertThat(used.publishAlong).isTrue()
        assertThat(used.nextNumber).isEqualTo("1.0.0")
        assertThat(used.uses.single().name).`as`("named as the catalog has it now").isEqualTo("Plain")
    }

    @Test
    fun `the view and the declaring follow the draft they name, not the next version`() {
        val tenant = setUp("borrow-draft")
        val work = withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters)), "Invoice").execute().withRequiredDataExample()
            MarkCatalogReady(testCatalog(tenant, letters)).execute()
            ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0").execute()
            CreateCatalogDraft(testCatalog(tenant, letters), "Redesign").execute()
        }
        val draft = CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(work.versionId))
        invoiceOnSharedTheme(tenant, draft)

        assertThat(withMediator { GetCatalogBorrowing(testCatalog(tenant, letters)).query() }.catalogs).`as`("the next version uses nothing").isEmpty()
        assertThat(withMediator { GetCatalogBorrowing(testCatalog(tenant, letters), draft).query() }.catalogs.map { it.catalogKey to it.declared }).containsExactly("shared" to true)

        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters), draft).query() }.map { it.catalogKey }).containsExactly("shared")
        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }).`as`("the next version declares nothing").isEmpty()
    }

    /** Letters' Invoice uses Shared's Brand, declared and pinned to Shared 1.0.0. */
    private fun tiedToShared(name: String): TenantKey {
        val tenant = setUp(name)
        invoiceOnSharedTheme(tenant)
        withMediator { declareDependency(tenant, letters, on = shared, release = "1.0.0") }
        return tenant
    }

    private fun releaseShared(tenant: TenantKey, version: String, change: () -> Unit) = withMediator {
        change()
        MarkCatalogReady(testCatalog(tenant, shared)).execute()
        ReleaseCatalogDraft(testCatalog(tenant, shared), version).execute()
    }

    @Test
    fun `a newer version that breaks nothing used here is offered, even when it breaks what is not used`() {
        val tenant = tiedToShared("borrow-newer-fits")
        // Removing Footer is breaking for Shared as a whole, but Letters does not use it.
        releaseShared(tenant, "2.0.0") { DeleteStencil(StencilId(StencilKey.of("footer"), testCatalog(TenantId(tenant), shared))).execute() }

        val used = withMediator { GetCatalogBorrowing(testCatalog(tenant, letters)).query() }.catalogs.single()

        assertThat(used.release).isEqualTo("1.0.0")
        assertThat(used.newerBreaks).isEmpty()
        assertThat(used.newerFits).`as`("judged by what Letters uses, not by the major number").isEqualTo("2.0.0")
        assertThat(used.newerDoesNotFit).isNull()
    }

    @Test
    fun `a newer version that breaks something used here is not offered, and says what and why`() {
        val tenant = setUp("borrow-newer-breaks")
        val footer = StencilId(StencilKey.of("footer"), testCatalog(TenantId(tenant), shared))
        withMediator {
            // Shared 1.1.0: Footer gets a placeholder `body`, which Letters' Invoice fills.
            releaseSharedNow(tenant, "1.1.0") { UpdateStencil(footer, content = footerWith("body")).execute() }
            val invoice = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))
            CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(
                VariantId(VariantKey.INITIAL, invoice),
                stencilDocument {
                    text("signed", "Signed")
                    placeholder("fill", "body", fill = listOf("signed"))
                    stencil("footer-1", "footer", fills = listOf("fill"), catalogKey = "shared")
                    build("footer-1")
                },
            ).execute()
            declareDependency(tenant, letters, on = shared)
        }
        // Shared 2.0.0 renames the placeholder: an interface change of something Letters uses.
        releaseShared(tenant, "2.0.0") { UpdateStencil(footer, content = footerWith("content")).execute() }

        val used = withMediator { GetCatalogBorrowing(testCatalog(tenant, letters)).query() }.catalogs.single()

        assertThat(used.release).isEqualTo("1.1.0")
        assertThat(used.newerFits).`as`("stays on 1.1.0").isNull()
        assertThat(used.newerDoesNotFit).isEqualTo("2.0.0")
        assertThat(used.newerBreaks.map { Triple(it.type, it.key, it.reason) })
            .containsExactly(Triple("stencil", "footer", "placeholder 'body' removed"))
        assertThat(used.newerBreaks.single().name).isEqualTo("Footer")
    }

    private fun footerWith(placeholder: String) = stencilDocument {
        text("line", "Acme")
        placeholder("slot", placeholder)
        build("line", "slot")
    }

    private fun releaseSharedNow(tenant: TenantKey, version: String, change: () -> Unit) {
        change()
        MarkCatalogReady(testCatalog(tenant, shared)).execute()
        ReleaseCatalogDraft(testCatalog(tenant, shared), version).execute()
    }
}
