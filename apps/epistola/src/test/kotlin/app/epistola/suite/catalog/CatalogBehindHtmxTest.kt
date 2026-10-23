// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod

/**
 * The published releases the next release lacks, on the catalog's page and on the next release's own
 * page, which show them from one fragment (`catalogs/behind.html`): one in a sentence that says what it
 * was made on, several as a group with a count, a drawing of the line and a row each with Merge….
 *
 * The catalog: 1.0.0; 1.0.1, a fix of the brand theme made on 1.0.0 in other work; 1.1.0 from the
 * next release; and, for two, 1.1.1, a fix of the letterhead theme made on 1.1.0 in other work. The
 * next release continues from the last one it published.
 */
class CatalogBehindHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: Tenant) = CatalogId(letters, TenantId(tenant.id))

    private fun theme(tenant: Tenant, key: String) = ThemeId(ThemeKey.of(key), catalogId(tenant))

    private fun work(tenant: Tenant, id: String) = CatalogReleaseRef(tenant.id, letters, DraftRef.Id(id))

    /** A fix of theme [key], renamed to [name], made in other work on [on] and published as [version]. */
    private fun fix(tenant: Tenant, key: String, name: String, on: String, version: String, acknowledged: Set<String> = emptySet()) = withMediator {
        val draft = work(tenant, CreateCatalogDraft(tenant.id, letters, "Fix $version", on).execute().versionId)
        UpdateTheme(theme(tenant, key), name = name, catalogVersion = draft).execute()
        MarkCatalogReady(tenant.id, letters, draft).execute()
        ReleaseCatalogDraft(tenant.id, letters, version, catalogVersion = draft, acknowledgedBehind = acknowledged).execute()
    }

    /** 1.0.0, then 1.0.1 from other work: the next release, on 1.0.0, lacks it. */
    private fun oneMissing(tenant: Tenant) {
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
            CreateTheme(theme(tenant, "brand"), "Brand").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalogId(tenant)), "Invoice").execute().withRequiredDataExample()
            MarkCatalogReady(tenant.id, letters).execute()
            ReleaseCatalogDraft(tenant.id, letters, "1.0.0").execute()
        }
        fix(tenant, "brand", "Brand 2", on = "1.0.0", version = "1.0.1")
    }

    /** [oneMissing], then 1.1.0 from the next release and 1.1.1 from other work: the next release, on 1.1.0, lacks 1.0.1 and 1.1.1. */
    private fun twoMissing(tenant: Tenant) {
        oneMissing(tenant)
        withMediator {
            CreateTheme(theme(tenant, "letterhead"), "Letterhead").execute()
            MarkCatalogReady(tenant.id, letters).execute()
            ReleaseCatalogDraft(tenant.id, letters, "1.1.0", acknowledgedBehind = setOf("1.0.1")).execute()
        }
        fix(tenant, "letterhead", "Letterhead 2", on = "1.1.0", version = "1.1.1", acknowledged = setOf("1.0.1"))
    }

    /** The catalog's page and the next release's own page, as a viewer holding [roles] (all, when null). */
    private fun pages(tenant: Tenant, roles: String? = null): List<String> = listOf("browse", "draft").map { page ->
        val headers = HttpHeaders().apply { roles?.let { set(TEST_TENANT_ROLES_HEADER, it) } }
        restTemplate.exchange("/tenants/${tenant.id}/catalogs/letters/$page", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java).body!!
    }

    private fun String.count(text: String): Int = windowed(text.length).count { it == text }

    /** The drawing's nodes as `version:kind`, in the order drawn. */
    private fun nodesOf(page: String): List<String> = Regex("""<g data-testid="catalog-draft-behind-node"([^>]*)>""").findAll(page).map { match ->
        val attributes = match.groupValues[1]
        val version = Regex("""data-version="([^"]*)"""").find(attributes)?.groupValues?.get(1) ?: "-"
        val kind = Regex("""data-kind="([^"]*)"""").find(attributes)!!.groupValues[1]
        "$version:$kind"
    }.toList()

    @Test
    fun `one missing release is a sentence saying what it was made on, on both pages, without a drawing`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Behind One") } }
        oneMissing(tenant)

        pages(tenant).forEach { page ->
            assertThat(page).contains("data-testid=\"catalog-draft-behind-release\" data-version=\"1.0.1\"")
                .contains("was made on v1.0.0 and is not in the next release: it changed Brand 2.")
                .contains("Merge it to bring its changes in")
                .doesNotContain("data-testid=\"catalog-draft-behind-title\"")
                .doesNotContain("data-testid=\"catalog-draft-behind-diagram\"")
            assertThat(page.count("data-testid=\"catalog-merge-open\"")).isEqualTo(1)
        }
    }

    @Test
    fun `two missing releases are a group with the count, a drawing of the line and a row each with Merge, on both pages`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Behind Two") } }
        twoMissing(tenant)

        pages(tenant).forEach { page ->
            assertThat(page).contains("data-testid=\"catalog-draft-behind-title\">2 releases are not in the next release yet<")
                .contains("They were published from separate work on earlier releases and never taken into the next release.")
                .contains("Merge the ones that belong in it.")
                .contains("data-testid=\"catalog-draft-behind-diagram\"")
            assertThat(Regex("""<svg[^>]*>""").findAll(page).map { it.value }.filter { "role=\"img\"" in it }.toList())
                .`as`("the drawing, named by what it shows")
                .anyMatch { "aria-label=\"Next release continues from v1.0.0 and v1.1.0. Not in it: v1.0.1, made on v1.0.0; v1.1.1, made on v1.1.0.\"" in it }
            assertThat(nodesOf(page)).containsExactly("1.0.0:line", "1.1.0:line", "-:draft", "1.0.1:behind", "1.1.1:behind")

            // One row each, oldest first, with where it was made, what it changed and Merge….
            assertThat(Regex("""data-testid="catalog-draft-behind-release" data-version="([^"]+)"""").findAll(page).map { it.groupValues[1] }.toList())
                .containsExactly("1.0.1", "1.1.1")
            assertThat(page).contains("/tenants/${tenant.id}/catalogs/letters/releases/1.0.1")
                .contains(">made on v1.0.0<").contains(">made on v1.1.0<")
                .contains(">Changed Brand 2<").contains(">Changed Letterhead 2<")
                .contains("/tenants/${tenant.id}/catalogs/letters/merge?release=1.0.1")
                .contains("/tenants/${tenant.id}/catalogs/letters/merge?release=1.1.1")
            assertThat(Regex("""published \d{4}-\d{2}-\d{2}""").findAll(page).count()).isEqualTo(2)
            assertThat(page.count("data-testid=\"catalog-merge-open\"")).isEqualTo(2)
            assertThat(page).doesNotContain("data-testid=\"catalog-merge-withheld\"")
        }
    }

    @Test
    fun `a viewer who may not merge sees no Merge, and why once`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Behind Viewer") } }
        twoMissing(tenant)

        pages(tenant, roles = "CONTENT_VIEWER,CONTENT_PUBLISHER").forEach { page ->
            assertThat(page).contains("data-testid=\"catalog-draft-behind-title\">2 releases are not in the next release yet<")
                .contains("Make the same changes here for the ones that belong in it.")
                .doesNotContain("data-testid=\"catalog-merge-open\"")
                .doesNotContain("Merge the ones that belong in it.")
            assertThat(page.count("data-testid=\"catalog-merge-withheld\"")).isEqualTo(1)
            assertThat(page).contains("Merging a published release into this draft needs the content author role.")
        }
    }
}
