// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/demo/catalog.json"

/**
 * The catalog-management list query, focused on its only non-trivial column:
 * the cheap AUTHORED working-copy drift flag (`CatalogListRow.pendingChanges`,
 * the list's `· pending changes` hint). Pins the timestamp baseline
 * `GREATEST(released_at, imported_at)` — in particular that a no-op re-import
 * does **not** register as drift (the false-positive the design was chosen to
 * avoid), and that only AUTHORED + released catalogs are ever flagged.
 */
class ListCatalogsForManagementTest : IntegrationTestBase() {

    /** Keys of catalogs the list query flags as drifted, for this tenant. */
    private fun pendingKeys(tenantKey: TenantKey): List<CatalogKey> = ListCatalogsForManagement(tenantKey).query()
        .filter { it.pendingChanges }
        .map { it.catalog.id }

    @Test
    fun `released catalog with no edits is not pending`() {
        val tenant = createTenant("Drift None")
        val key = CatalogKey.of("drift-none")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Drift None").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()

            assertThat(pendingKeys(tenant.id)).doesNotContain(key)
        }
    }

    @Test
    fun `editing a released catalog makes it pending`() {
        val tenant = createTenant("Drift Edited")
        val key = CatalogKey.of("drift-edited")
        val catalogId = CatalogId(key, TenantId(tenant.id))
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Drift Edited").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()

            // A resource changed after the release → working copy drifted.
            CreateTheme(id = ThemeId(ThemeKey.of("added"), catalogId), name = "Added After Release").execute()

            assertThat(pendingKeys(tenant.id)).contains(key)
        }
    }

    @Test
    fun `re-importing an identical ZIP does NOT make a released catalog pending`() {
        val tenant = createTenant("Drift Reimport")
        val key = CatalogKey.of("drift-reimport")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Drift Reimport").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()
            val zip = ExportCatalogZip(tenantKey = tenant.id, catalogKey = key).execute().zipBytes

            // Re-import bumps resources' updated_at, but also imported_at (set
            // after the upserts) — so the baseline keeps pace and this is NOT
            // mistaken for drift. (The regression the design targets.)
            ImportCatalogZip(tenantKey = tenant.id, zipBytes = zip, catalogType = CatalogType.AUTHORED).execute()

            assertThat(pendingKeys(tenant.id)).doesNotContain(key)
        }
    }

    @Test
    fun `a never-released catalog is never pending (even when edited)`() {
        val tenant = createTenant("Drift Unreleased")
        val key = CatalogKey.of("drift-unreleased")
        val catalogId = CatalogId(key, TenantId(tenant.id))
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Drift Unreleased").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("more"), catalogId), name = "More").execute()

            // No release → you can't drift from one; the list shows "unreleased".
            assertThat(pendingKeys(tenant.id)).doesNotContain(key)
        }
    }

    @Test
    fun `a SUBSCRIBED catalog is never pending`() {
        val tenant = createTenant("Drift Subscribed")
        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            assertThat(pendingKeys(tenant.id)).doesNotContain(CatalogKey.of("epistola-demo"))
        }
    }
}
