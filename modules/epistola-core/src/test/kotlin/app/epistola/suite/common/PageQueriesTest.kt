// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.ListCodeListPage
import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitionPage
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.ListCatalogPage
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleasePage
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.queries.ListEnvironmentPage
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.queries.ListFontPage
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.queries.variants.ListVariantPage
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.tenants.queries.ListTenantPage
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.ListThemePage
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Every `…Page` query cuts its list in the database ([Paging]). Each is held against the full list its
 * sibling query answers: walked page by page at every size, the pages are that list in its order,
 * each with its whole count, and a page past the end is empty but still knows the count.
 */
class PageQueriesTest : IntegrationTestBase() {

    @Test
    fun `fonts page like their list`() = withCatalog { tenantId, catalog ->
        listOf("Charlie", "Alpha", "Bravo").forEach { ImportFont(catalog = catalog, slug = it.lowercase(), name = it, kind = "sans").execute() }
        assertPagesOf(ListFonts(tenantId, catalog).query()) { ListFontPage(tenantId, catalog, page = it).query() }
    }

    @Test
    fun `code lists page like their list`() = withCatalog { tenantId, catalog ->
        listOf("c", "a", "b").forEach {
            CreateCodeList(CodeListId(CodeListKey.of("list-$it"), catalog), "List $it", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("k", "v"))).execute()
        }
        assertPagesOf(ListCodeLists(tenantId, catalog).query()) { ListCodeListPage(tenantId, catalog, page = it).query() }
    }

    @Test
    fun `attributes page like their list`() = withCatalog { tenantId, catalog ->
        listOf("c", "a", "b").forEach { CreateAttributeDefinition(AttributeId(AttributeKey.of("attr-$it"), catalog), "Attr $it").execute() }
        assertPagesOf(ListAttributeDefinitions(tenantId, catalog).query()) { ListAttributeDefinitionPage(tenantId, catalog, page = it).query() }
    }

    @Test
    fun `themes page like their list, searched too`() = withCatalog { tenantId, catalog ->
        listOf("c", "a", "b", "d").forEach { CreateTheme(ThemeId(ThemeKey.of("theme-$it"), catalog), "Theme $it").execute() }
        assertPagesOf(ListThemes(tenantId, catalog).query()) { ListThemePage(tenantId, catalog, page = it).query() }
        assertPagesOf(ListThemes(tenantId, catalog, searchTerm = "Theme").query()) { ListThemePage(tenantId, catalog, searchTerm = "Theme", page = it).query() }
    }

    @Test
    fun `variants page like their list`() = withCatalog { _, catalog ->
        val template = TemplateId(TemplateKey.of("letter"), catalog)
        CreateDocumentTemplate(template, "Letter").execute()
        listOf("c", "a", "b").forEach { CreateVariant(VariantId(VariantKey.of("variant-$it"), template), "Variant $it", description = null).execute() }
        assertPagesOf(ListVariants(template).query()) { ListVariantPage(template, page = it).query() }
    }

    @Test
    fun `catalogs page like their list`() = withCatalog { tenantId, _ ->
        listOf("c", "a", "b").forEach { CreateCatalog(tenantKey = tenantId.key, id = CatalogKey.of("more-$it"), name = "More $it").execute() }
        assertPagesOf(ListCatalogs(tenantId.key).query()) { ListCatalogPage(tenantId.key, page = it).query() }
    }

    @Test
    fun `environments page like their list`() = withCatalog { tenantId, _ ->
        listOf("c", "a", "b").forEach { CreateEnvironment(EnvironmentId(EnvironmentKey.of("env-$it"), tenantId), "Env $it").execute() }
        assertPagesOf(ListEnvironments(tenantId).query()) { ListEnvironmentPage(tenantId, page = it).query() }
    }

    @Test
    fun `tenants page like their list`() {
        val marker = UUID.randomUUID().toString().take(8)
        repeat(3) { createTenant("Paged $marker $it") }
        withMediator {
            assertPagesOf(ListTenants(searchTerm = marker).query()) { ListTenantPage(searchTerm = marker, page = it).query() }
        }
    }

    @Test
    fun `catalog versions page like their list, drafts first and releases highest first`() = withCatalog { _, catalog ->
        CreateTheme(ThemeId(ThemeKey.of("th1"), catalog), "T1").execute()
        // 1.10.0 after 1.2.0: by number, not as text.
        listOf("1.0.0", "1.2.0", "1.10.0", "2.0.0").forEach { version ->
            MarkCatalogReady(catalog).execute()
            ReleaseCatalogDraft(catalog = catalog, version = version).execute()
        }
        val all = ListCatalogReleases(catalog).query()
        assertThat(all.map { it.version }).containsExactly(null, "2.0.0", "1.10.0", "1.2.0", "1.0.0")
        assertPagesOf(all) { ListCatalogReleasePage(catalog, page = it).query().let { page -> ResultPage(page.items, page.total.toLong()) } }
        CatalogReleaseStatus.entries.forEach { status ->
            assertPagesOf(ListCatalogReleases(catalog, status).query()) { ListCatalogReleasePage(catalog, status, it).query().let { page -> ResultPage(page.items, page.total.toLong()) } }
        }
    }

    private fun withCatalog(block: (TenantId, app.epistola.suite.common.ids.CatalogId) -> Unit) {
        val tenant = createTenant("Paging")
        val catalogKey = CatalogKey.of("paged")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Paged").execute()
            block(TenantId(tenant.id), testCatalog(tenant.id, catalogKey))
        }
    }

    private fun <T> assertPagesOf(all: List<T>, pageOf: (PageRequest) -> ResultPage<T>) {
        assertThat(all).describedAs("enough rows to cut into pages").hasSizeGreaterThanOrEqualTo(1)
        for (size in 1..all.size + 1) {
            val walked = generateSequence(0) { it + size }.takeWhile { it < all.size }.map { offset ->
                val page = pageOf(PageRequest(limit = size, offset = offset))
                assertThat(page.total).describedAs("total at size $size, offset $offset").isEqualTo(all.size.toLong())
                page.items
            }.flatten().toList()
            assertThat(walked).describedAs("pages of $size in order").containsExactlyElementsOf(all)
        }
        val pastTheEnd = pageOf(PageRequest(limit = 2, offset = all.size + 5))
        assertThat(pastTheEnd.items).isEmpty()
        assertThat(pastTheEnd.total).describedAs("a page past the end still knows the count").isEqualTo(all.size.toLong())
    }
}
