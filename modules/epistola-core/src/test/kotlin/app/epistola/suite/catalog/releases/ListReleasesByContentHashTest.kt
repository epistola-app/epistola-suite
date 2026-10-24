// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.recordBeforeContentHashes
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A catalog's releases found by their content hash (ADR 0034, contract
 * `listCatalogReleases?contentHash=`), filtered, ordered and paged in the database.
 *
 * Catalog `letters`: 1.0.0, then 1.0.1 and 1.0.2 with the same content (new notes only), then
 * 1.1.0 changed. Catalog `forms` holds the same content as 1.0.0.
 */
class ListReleasesByContentHashTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")
    private val forms = CatalogKey.of("forms")

    private fun released(tenant: TenantKey, key: CatalogKey, version: String) = withMediator {
        CreateCatalog(tenantKey = tenant, id = key, name = key.value).execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), key)), "Brand").execute()
        MarkCatalogReady(testCatalog(tenant, key)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, key), version = version).execute()
    }

    /** The tenant described above; the content hash of 1.0.0. */
    private fun seeded(tenant: TenantKey): String = withMediator {
        released(tenant, letters, "1.0.0")
        // The same content again, under two more numbers: only the notes are new.
        ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.0.1", notes = "Notes only").execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.0.2", notes = "Notes again").execute()
        UpdateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), letters)), name = "Brand, changed").execute()
        MarkCatalogReady(testCatalog(tenant, letters)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.1.0").execute()
        released(tenant, forms, "1.0.0")
        GetCatalogRelease(testCatalog(tenant, letters), "1.0.0").query()!!.contentHash!!
    }

    @Test
    fun `the catalog's releases with that content, highest first, and no other catalog's`() {
        val tenant = createTenant("By content hash").id
        val hash = seeded(tenant)
        withMediator {
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
            CreateEnvironment(production, "Production").execute()
            DeployCatalogRelease(production, testCatalog(production.tenantKey, letters), "1.0.1").execute()
        }

        val found = withMediator { ListReleasesByContentHash(testCatalog(tenant, letters), hash).query() }

        assertThat(found.items.map { it.version }).containsExactly("1.0.2", "1.0.1", "1.0.0")
        assertThat(found.total).isEqualTo(3)
        assertThat(found.items).allSatisfy {
            assertThat(it.status).isEqualTo(CatalogReleaseStatus.RELEASED)
            assertThat(it.contentHash).isEqualTo(hash)
        }
        assertThat(found.items.single { it.version == "1.0.1" }.deployedTo).containsExactly("production")
        assertThat(found.items.single { it.version == "1.0.1" }.notes).isEqualTo("Notes only")
        assertThat(withMediator { ListReleasesByContentHash(testCatalog(tenant, letters), "v6:${"0".repeat(64)}").query() })
            .isEqualTo(CatalogReleasePage(emptyList(), 0))
    }

    @Test
    fun `a page is cut in the query, with the count of every match`() {
        val tenant = createTenant("By content hash paged").id
        val hash = seeded(tenant)

        val first = withMediator { ListReleasesByContentHash(testCatalog(tenant, letters), hash, limit = 2, offset = 0).query() }
        val second = withMediator { ListReleasesByContentHash(testCatalog(tenant, letters), hash, limit = 2, offset = 2).query() }
        val past = withMediator { ListReleasesByContentHash(testCatalog(tenant, letters), hash, limit = 2, offset = 4).query() }

        assertThat(first.items.map { it.version }).containsExactly("1.0.2", "1.0.1")
        assertThat(first.total).isEqualTo(3)
        assertThat(second.items.map { it.version }).containsExactly("1.0.0")
        assertThat(second.total).isEqualTo(3)
        assertThat(past.items).isEmpty()
    }

    @Test
    fun `a release whose content hash is not computed yet is no match`() {
        val tenant = createTenant("By content hash pending").id
        val hash = seeded(tenant)
        withMediator { mediator.recordBeforeContentHashes(jdbi, testCatalog(tenant, letters), "1.0.1") }

        val found = withMediator { ListReleasesByContentHash(testCatalog(tenant, letters), hash).query() }

        assertThat(found.items.map { it.version }).containsExactly("1.0.2", "1.0.0")
    }
}
