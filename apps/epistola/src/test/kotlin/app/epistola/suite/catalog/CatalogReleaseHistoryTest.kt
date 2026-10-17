// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
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

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `Versions lists published versions newest first, and each one's page offers its export and notes`() {
        val tenant = tenantWithCatalog("hist-cat")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-cat"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogVersion(tenant.id, catalog.key, version = "1.0.0", notes = "first cut").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("second"), catalog), name = "Second").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogVersion(tenant.id, catalog.key, version = "1.10.0").execute()
        }

        val body = versions(browse(tenant.id.value, "hist-cat"))

        // Newest first, and by version components: v1.10.0 is newer than v1.9.0 or v1.0.0, which
        // sorting the text would get wrong.
        assertThat(body.indexOf(">v1.10.0<")).isLessThan(body.indexOf(">v1.0.0<"))
        assertThat(body).doesNotContain("contents not kept")
        assertThat(body).contains("href=\"/tenants/${tenant.id.value}/catalogs/hist-cat/releases/1.10.0\"")
        assertThat(release(tenant.id.value, "hist-cat", "1.0.0")).contains("first cut")
        assertThat(release(tenant.id.value, "hist-cat", "1.10.0")).contains("catalogs/hist-cat/export?version=1.10.0")
    }

    @Test
    fun `a release that kept no content says so and is not offered for export`() {
        val tenant = tenantWithCatalog("hist-legacy")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-legacy"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogVersion(tenant.id, catalog.key, version = "1.0.0").execute()
        }
        forgetRetainedContent(tenant.id.value, "hist-legacy")

        assertThat(versions(browse(tenant.id.value, "hist-legacy"))).contains("contents not kept")
        val body = release(tenant.id.value, "hist-legacy", "1.0.0")
        assertThat(body).contains("content not kept")
        // Offering an export that would refuse at download is the failure this guards against.
        assertThat(body).doesNotContain("catalogs/hist-legacy/export?version=1.0.0")
    }

    @Test
    fun `a subscribed catalog lists the release it installed, and offers no way to cut one`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Release History Subscribed") } }

        val body = browse(tenant.id.value, "system")

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
            ReleaseCatalogVersion(tenant.id, catalog.key, version = "1.0.0").execute()
        }

        val body = release(tenant.id.value, "hist-publish", "1.0.0")

        // Nothing is offered without somewhere to send it — this tenant is not connected to
        // Exchange, so the page carries the export action and no publish action.
        assertThat(body).contains("catalogs/hist-publish/export?version=1.0.0")
        assertThat(body).doesNotContain("data-testid=\"release-exchange-publish\"")
    }

    /**
     * The only way to reclaim what releases hold, so the row has to offer it — and stop offering it
     * once there is nothing left to drop, which is the same state a release cut before Epistola
     * retained content has always had.
     */
    @Test
    fun `a retained release offers to forget its content, and stops once it has`() {
        val tenant = tenantWithCatalog("hist-forget")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-forget"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogVersion(tenant.id, catalog.key, version = "1.0.0").execute()
        }

        assertThat(release(tenant.id.value, "hist-forget", "1.0.0"))
            .`as`("there is content to drop")
            .contains("data-testid=\"release-forget\"")

        // The redirect is followed by the test client, so the body here is where it landed: the
        // version's page, already showing its content gone.
        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/hist-forget/releases/1.0.0/forget",
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )
        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(response.body).`as`("lands back on the version's page").contains("<h2>As published</h2>")

        assertThat(versions(browse(tenant.id.value, "hist-forget"))).`as`("the version is still listed").contains(">v1.0.0<").contains("contents not kept")
        val after = release(tenant.id.value, "hist-forget", "1.0.0")
        assertThat(after).`as`("and says its content is gone").contains("content not kept")
        assertThat(after)
            .`as`("nothing left to forget, so the action goes")
            .doesNotContain("data-testid=\"release-forget\"")
        assertThat(after)
            .`as`("and it can no longer be exported as released")
            .doesNotContain("catalogs/hist-forget/export?version=1.0.0")
    }

    /**
     * Deleting a release from its row. The two destructive actions differ in what survives, so this
     * pins the difference the confirm dialogs describe: forgetting leaves the release listed, this
     * does not.
     */
    @Test
    fun `a published version can be deleted, and the catalog follows`() {
        val tenant = tenantWithCatalog("hist-delete")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-delete"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogVersion(tenant.id, catalog.key, version = "1.0.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("second"), catalog), name = "Second").execute()
            MarkCatalogReady(tenant.id, catalog.key).execute()
            ReleaseCatalogVersion(tenant.id, catalog.key, version = "1.1.0").execute()
        }

        assertThat(release(tenant.id.value, "hist-delete", "1.1.0")).contains("data-testid=\"release-delete\"")

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/hist-delete/releases/1.1.0/delete",
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )
        assertThat(response.statusCode.is2xxSuccessful).isTrue()

        val after = versions(browse(tenant.id.value, "hist-delete"))
        assertThat(after).`as`("gone from the list, unlike forgetting its content").doesNotContain(">v1.1.0<")
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

    /**
     * Raw SQL: makes a release look like one cut before releases kept their content, a shape no
     * command can produce. Clears the flag as well as the rows, because the flag is the fact — a
     * release that kept nothing is not the same as one that contained nothing.
     *
     * The core module has the same helper, but test sources do not cross module boundaries, so this
     * is a deliberate second copy rather than an oversight.
     */
    private fun forgetRetainedContent(tenantKey: String, catalogKey: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("DELETE FROM release_entries WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey).bind("c", catalogKey).execute()
        handle.createUpdate("UPDATE catalog_releases SET content_retained = FALSE WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey).bind("c", catalogKey).execute()
    }

    /** The Versions card of a catalog page. */
    private fun versions(page: String): String = page.substringAfter("data-testid=\"catalog-versions\"").substringBefore("data-testid=\"catalog-deployments\"")

    private fun release(tenantKey: String, catalogKey: String, version: String): String = restTemplate
        .getForEntity("/tenants/$tenantKey/catalogs/$catalogKey/releases/$version", String::class.java)
        .body!!

    private fun browse(tenantKey: String, catalogKey: String): String = restTemplate
        .getForEntity("/tenants/$tenantKey/catalogs/$catalogKey/browse", String::class.java)
        .body!!
}
