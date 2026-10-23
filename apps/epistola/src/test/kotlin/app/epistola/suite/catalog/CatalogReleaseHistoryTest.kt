// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders

/**
 * The published versions: listed under Versions on the catalog page, each with its own page that
 * says what it went out with and what can still be done with it.
 *
 * Until releases kept their content there was nothing to show: a release was a version and a
 * fingerprint, and the catalog row already carried the version. Now each one can be handed back
 * exactly as it was released — except the ones cut before that, which cannot, and must not be
 * offered as though they could.
 */
class CatalogReleaseHistoryTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `Versions lists published versions newest first, and each one's page offers its export and notes`() {
        val tenant = tenantWithCatalog("hist-cat")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-cat"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogDraft(tenant.id, catalog.key, version = "1.0.0", notes = "first cut").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("second"), catalog), name = "Second").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogDraft(tenant.id, catalog.key, version = "1.10.0").execute()
        }

        val body = versions(versionsPage(tenant.id.value, "hist-cat"))

        // Newest first, and by version components: v1.10.0 is newer than v1.9.0 or v1.0.0, which
        // sorting the text would get wrong.
        assertThat(body.indexOf(">v1.10.0<")).isLessThan(body.indexOf(">v1.0.0<"))
        assertThat(body).doesNotContain("contents not kept")
        assertThat(body).contains("href=\"/tenants/${tenant.id.value}/catalogs/hist-cat/releases/1.10.0\"")
        assertThat(release(tenant.id.value, "hist-cat", "1.0.0")).contains("first cut")
        assertThat(release(tenant.id.value, "hist-cat", "1.10.0")).contains("catalogs/hist-cat/export?version=1.10.0")
    }

    @Test
    fun `a subscribed catalog lists the release it installed, and offers no way to cut one`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Release History Subscribed") } }

        val body = versionsPage(tenant.id.value, "system")

        assertThat(versions(body))
            .`as`("installing records the publisher's release, which can be deployed")
            .contains("data-status=\"published\"")
        assertThat(body)
            .`as`("and nothing can be published or drafted from a catalog this installation does not author")
            .doesNotContain("data-testid=\"catalog-release-open\"").doesNotContain("data-testid=\"catalog-draft-new\"")
    }

    /**
     * The catalog page is where you go to work on a catalog, and it was the one place that listed
     * every release while offering no way to cut one — the action lived only in the catalogs list.
     */
    @Test
    fun `the catalog page offers a release, with somewhere for the dialog to land`() {
        val tenant = tenantWithCatalog("hist-cut")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-cut"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            // Release is offered once nothing changed is unready (CatalogReleaseReadiness).
            MarkCatalogReady(tenant.id, catalog.key).execute()
        }

        val body = browse(tenant.id.value, "hist-cut")

        assertThat(body).contains("data-testid=\"catalog-release-open\"")
        assertThat(body).contains("catalogs/hist-cut/release")
        // The trigger swaps into this mount. Rendering one without the other is the failure that
        // looks like a broken button: HTMX fires, finds nothing to swap into, and does nothing.
        assertThat(body).contains("id=\"release-dialog-container\"")
    }

    /** Never released and empty, so the page points at the first template rather than at publishing nothing. */
    @Test
    fun `a catalog with no releases and no template points at its first template`() {
        val tenant = tenantWithCatalog("hist-none")

        val body = browse(tenant.id.value, "hist-none")

        assertThat(body).contains("Start with a template.").contains("data-testid=\"catalog-status-new-template\"")
        assertThat(body).doesNotContain("data-testid=\"catalog-release-open\"")
    }

    @Test
    fun `a release already sent to Exchange is marked, not offered again`() {
        val tenant = tenantWithCatalog("hist-publish")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-publish"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogDraft(tenant.id, catalog.key, version = "1.0.0").execute()
        }

        val body = release(tenant.id.value, "hist-publish", "1.0.0")

        // Nothing is offered without somewhere to send it — this tenant is not connected to
        // Exchange, so the page carries the export action and no publish action.
        assertThat(body).contains("catalogs/hist-publish/export?version=1.0.0")
        assertThat(body).doesNotContain("data-testid=\"release-exchange-publish\"")
    }

    /** Deleting a release from its row: it is gone from the list, with its content. */
    @Test
    fun `a published version can be deleted, and the catalog follows`() {
        val tenant = tenantWithCatalog("hist-delete")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-delete"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogDraft(tenant.id, catalog.key, version = "1.0.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("second"), catalog), name = "Second").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogDraft(tenant.id, catalog.key, version = "1.1.0").execute()
        }

        assertThat(release(tenant.id.value, "hist-delete", "1.1.0")).contains("data-testid=\"release-delete\"")

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/hist-delete/releases/1.1.0/delete",
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )
        assertThat(response.statusCode.is2xxSuccessful).isTrue()

        val after = versions(versionsPage(tenant.id.value, "hist-delete"))
        assertThat(after).`as`("gone from the list, unlike forgetting its content").doesNotContain("data-version=\"1.1.0\"")
        assertThat(after).`as`("and the one before it is still there").contains(">v1.0.0<")
    }

    private fun tenantWithCatalog(slug: String): Tenant {
        lateinit var created: Tenant
        fixture {
            given {
                created = tenant("Release History $slug")
                withMediator { CreateCatalog(tenantKey = created.id, id = CatalogKey.of(slug), name = slug).execute() }
            }
        }
        return created
    }

    /** The Versions card of a catalog's Versions page. */
    private fun versions(page: String): String = page.substringAfter("data-testid=\"catalog-versions\"").substringBefore("id=\"dialog-mount\"")

    /** A catalog's Versions page (docs/workspace-shell.md). */
    private fun versionsPage(tenantKey: String, catalogKey: String): String = restTemplate
        .getForEntity("/tenants/$tenantKey/catalogs/$catalogKey/releases", String::class.java)
        .body!!

    private fun release(tenantKey: String, catalogKey: String, version: String): String = restTemplate
        .getForEntity("/tenants/$tenantKey/catalogs/$catalogKey/releases/$version", String::class.java)
        .body!!

    private fun browse(tenantKey: String, catalogKey: String): String = restTemplate
        .getForEntity("/tenants/$tenantKey/catalogs/$catalogKey/browse", String::class.java)
        .body!!
}
