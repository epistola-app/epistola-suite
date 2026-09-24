// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * [GetCatalogResourceChanges] localizes the catalog-level drift that
 * [GetCatalogReleaseStatus] reports to the resources that caused it, against the resources
 * [ReleaseCatalogVersion] records for each release (V20260923201010).
 */
class GetCatalogResourceChangesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `nothing released yet makes every resource new`() {
        val catalog = authoredCatalog("changes-new")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), catalog), name = "T1").execute()

            val changes = GetCatalogResourceChanges(catalog.tenantKey, catalog.key).query()

            assertThat(changes.latestVersion).isNull()
            assertThat(changes.baselineAvailable).isTrue()
            assertThat(changes.hasUnreleasedChanges).isTrue()
            assertThat(changes.resources).singleElement().satisfies({
                assertThat(it.type).isEqualTo("theme")
                assertThat(it.slug).isEqualTo("th1")
                assertThat(it.name).isEqualTo("T1")
                assertThat(it.state).isEqualTo(CatalogResourceState.NEW)
            })
        }
    }

    @Test
    fun `a freshly released catalog has no unreleased changes`() {
        val catalog = authoredCatalog("changes-released")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), catalog), name = "T1").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("th2"), catalog), name = "T2").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()

            val changes = GetCatalogResourceChanges(catalog.tenantKey, catalog.key).query()

            assertThat(changes.latestVersion).isEqualTo("1.0.0")
            assertThat(changes.hasUnreleasedChanges).isFalse()
            assertThat(changes.resources.map { it.state })
                .containsOnly(CatalogResourceState.RELEASED)
        }
    }

    @Test
    fun `editing one resource leaves the others released`() {
        val catalog = authoredCatalog("changes-edit")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("edited"), catalog), name = "Edited").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("untouched"), catalog), name = "Untouched").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()

            UpdateTheme(id = ThemeId(ThemeKey.of("edited"), catalog), name = "Edited again").execute()

            val changes = GetCatalogResourceChanges(catalog.tenantKey, catalog.key).query()

            assertThat(changes.hasUnreleasedChanges).isTrue()
            assertThat(changes.resources).extracting({ it.slug }, { it.state }).containsExactly(
                tuple("edited", CatalogResourceState.MODIFIED),
                tuple("untouched", CatalogResourceState.RELEASED),
            )
        }
    }

    @Test
    fun `a resource added after the release is new and a deleted one is removed`() {
        val catalog = authoredCatalog("changes-add-remove")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("kept"), catalog), name = "Kept").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("dropped"), catalog), name = "Dropped").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()

            DeleteTheme(id = ThemeId(ThemeKey.of("dropped"), catalog)).execute()
            CreateTheme(id = ThemeId(ThemeKey.of("added"), catalog), name = "Added").execute()

            val changes = GetCatalogResourceChanges(catalog.tenantKey, catalog.key).query()

            // The dropped theme is named although the working copy no longer has it: the release
            // recorded what it contained, so a removed resource is still identifiable.
            assertThat(changes.resources).extracting({ it.slug }, { it.state }, { it.name }).containsExactly(
                tuple("added", CatalogResourceState.NEW, "Added"),
                tuple("dropped", CatalogResourceState.REMOVED, "Dropped"),
                tuple("kept", CatalogResourceState.RELEASED, "Kept"),
            )
        }
    }

    @Test
    fun `a release with no recorded baseline still reports released while the content matches`() {
        val catalog = authoredCatalog("changes-legacy-clean")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), catalog), name = "T1").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }
        forgetBaseline(catalog)

        withMediator {
            val changes = GetCatalogResourceChanges(catalog.tenantKey, catalog.key).query()

            // The fingerprint still matches, so the working digests are that release's digests.
            assertThat(changes.baselineAvailable).isTrue()
            assertThat(changes.resources.map { it.state }).containsOnly(CatalogResourceState.RELEASED)
        }
    }

    @Test
    fun `a release with no recorded baseline cannot attribute changes once the content differs`() {
        val catalog = authoredCatalog("changes-legacy-dirty")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), catalog), name = "T1").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }
        forgetBaseline(catalog)

        withMediator {
            UpdateTheme(id = ThemeId(ThemeKey.of("th1"), catalog), name = "T1 edited").execute()

            val changes = GetCatalogResourceChanges(catalog.tenantKey, catalog.key).query()

            assertThat(changes.baselineAvailable).isFalse()
            assertThat(changes.hasUnreleasedChanges).isTrue()
            assertThat(changes.resources.map { it.state }).containsOnly(CatalogResourceState.UNKNOWN)

            // The next release records one, and the detail comes back.
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.1").execute()
            assertThat(GetCatalogResourceChanges(catalog.tenantKey, catalog.key).query().baselineAvailable).isTrue()
        }
    }

    private fun authoredCatalog(slug: String): CatalogId {
        val tenant = createTenant(slug)
        val catalogKey = CatalogKey.of(slug)
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = slug).execute()
        }
        return CatalogId(catalogKey, TenantId(tenant.id))
    }

    /**
     * Makes a release look like one cut before V20260923201010, when a release began recording its
     * resources. No command can produce that row any more, and the fallback it exercises exists
     * only for those releases.
     */
    private fun forgetBaseline(catalog: CatalogId) {
        jdbi.useHandle<Exception> { handle ->
            val removed = handle.createUpdate(
                "DELETE FROM release_entries WHERE tenant_key = :t AND catalog_key = :c",
            )
                .bind("t", catalog.tenantKey)
                .bind("c", catalog.key)
                .execute()
            assertThat(removed).isPositive()
        }
    }
}
