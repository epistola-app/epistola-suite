package app.epistola.suite.backups

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Restore round-trip across two non-system catalogs with a cross-catalog code-list binding —
 * the case that exercises both the destructive-wipe ordering (the attribute→code-list FK is
 * `ON DELETE RESTRICT`) and the dependency-ordered re-import (the `main` catalog must import
 * after the `shared` catalog whose code list it binds).
 */
class RestoreTenantSnapshotIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `restore reproduces a tenant with a cross-catalog code-list binding and drops divergence`() {
        val tenant = createTenant("Restore Xcat")
        val tenantId = TenantId(tenant.id)
        val shared = CatalogKey.of("shared")
        val main = CatalogKey.of("main")
        val regionAttr = AttributeId(AttributeKey.of("region"), CatalogId(main, tenantId))
        val regionsCodeList = CodeListId(CodeListKey.of("regions"), CatalogId(shared, tenantId))

        val brandTheme = ThemeId(ThemeKey.of("brand"), CatalogId(main, tenantId))

        // shared: an inline code list. main: an attribute bound to it (cross-catalog) + a theme that
        // is set as the tenant's default theme (exercises the fk_tenants_default_theme NO ACTION FK).
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = shared, name = "Shared").execute()
            CreateCodeList(
                id = regionsCodeList,
                displayName = "Regions",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("eu", "Europe"), CodeListEntry("us", "United States")),
            ).execute()

            CreateCatalog(tenantKey = tenant.id, id = main, name = "Main").execute()
            CreateAttributeDefinition(
                id = regionAttr,
                displayName = "Region",
                codeListId = regionsCodeList,
            ).execute()
            CreateTheme(id = brandTheme, name = "Brand").execute()
            SetTenantDefaultTheme(tenantId = tenant.id, themeId = ThemeKey.of("brand"), catalogKey = main).execute()
        }

        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }
        val originalFingerprint = snapshot.snapshotFingerprint

        // main depends on shared in the manifest, so restore orders shared first.
        val mainEntry = readManifest(snapshot.bytes).catalogs.single { it.catalogKey == "main" }
        assertThat(mainEntry.dependsOnCatalogKeys).contains("shared")

        // Diverge: add a stray catalog that is NOT in the snapshot.
        withMediator { CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("stray"), name = "Stray").execute() }

        // Restore (destructive).
        runAs(backupSystemPrincipal(tenant.id)) {
            RestoreTenantSnapshot(tenant.id, snapshot.bytes).execute()
        }

        withMediator {
            // The stray catalog is gone; the snapshot catalogs and the system catalog are present.
            val catalogKeys = ListCatalogs(tenant.id).query().map { it.id.value }
            assertThat(catalogKeys).contains("shared", "main")
            assertThat(catalogKeys).doesNotContain("stray")
            assertThat(GetCatalog(tenant.id, SYSTEM_CATALOG_KEY).query()).isNotNull()

            // The cross-catalog binding survived the restore.
            val attr = GetAttributeDefinition(regionAttr).query()
            assertThat(attr).isNotNull()
            assertThat(attr!!.codeListCatalogKey?.value).isEqualTo("shared")
            assertThat(attr.codeListSlug?.value).isEqualTo("regions")

            // The tenant default theme (which the wipe had to release to delete its catalog) is
            // re-applied after restore — the C1 regression case.
            val restoredTenant = GetTenant(tenant.id).query()
            assertThat(restoredTenant!!.defaultThemeKey?.value).isEqualTo("brand")
            assertThat(restoredTenant.defaultThemeCatalogKey?.value).isEqualTo("main")

            // The restored tenant is content-identical to the snapshot.
            val rebuilt = BuildTenantSnapshot(tenant.id).execute()
            assertThat(rebuilt.snapshotFingerprint).isEqualTo(originalFingerprint)
        }
    }

    private fun readManifest(bytes: ByteArray): SnapshotManifest {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "snapshot.json") {
                    return objectMapper.readValue(zip.readBytes(), SnapshotManifest::class.java)
                }
                entry = zip.nextEntry
            }
        }
        error("snapshot.json not found")
    }
}
