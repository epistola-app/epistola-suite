// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
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

/**
 * The release history a catalog page shows.
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
    fun `the catalog page lists releases newest first and offers each retained one`() {
        val tenant = tenantWithCatalog("hist-cat")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-cat"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalog.key, version = "1.0.0", notes = "first cut").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("second"), catalog), name = "Second").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalog.key, version = "1.10.0").execute()
        }

        val body = browse(tenant.id.value, "hist-cat")

        assertThat(body).contains("Releases")
        // Newest first, and by version components: v1.10.0 is newer than v1.9.0 or v1.0.0, which
        // sorting the text would get wrong.
        assertThat(body.indexOf("v1.10.0")).isLessThan(body.indexOf("v1.0.0"))
        assertThat(body).contains("first cut")
        assertThat(body).contains("2 resources, kept")
        assertThat(body).contains("catalogs/hist-cat/export?version=1.10.0")
    }

    @Test
    fun `a release that kept no content says so and is not offered for export`() {
        val tenant = tenantWithCatalog("hist-legacy")

        withMediator {
            val catalog = CatalogId(CatalogKey.of("hist-legacy"), TenantId(tenant.id))
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalog.key, version = "1.0.0").execute()
        }
        forgetReleaseEntries(tenant.id.value, "hist-legacy")

        val body = browse(tenant.id.value, "hist-legacy")

        assertThat(body).contains("not kept")
        // Offering an export that would refuse at download is the failure this guards against.
        assertThat(body).doesNotContain("catalogs/hist-legacy/export?version=1.0.0")
    }

    @Test
    fun `a subscribed catalog shows no release history of its own`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Release History Subscribed") } }

        val body = browse(tenant.id.value, "system")

        assertThat(body)
            .`as`("a subscribed catalog records the release it installed, not releases it cut")
            .doesNotContain("<h2>Releases</h2>")
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
     * Raw SQL: makes a release look like one cut before `V20260923201010`, a shape no command can
     * produce. The core module has the same helper, but test sources do not cross module
     * boundaries, so this is a deliberate second copy rather than an oversight.
     */
    private fun forgetReleaseEntries(tenantKey: String, catalogKey: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("DELETE FROM release_entries WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .execute()
    }

    private fun browse(tenantKey: String, catalogKey: String): String = restTemplate
        .getForEntity("/tenants/$tenantKey/catalogs/$catalogKey/browse", String::class.java)
        .body!!
}
