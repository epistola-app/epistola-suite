// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.snapshot

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
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
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Restore round-trip across two non-system catalogs with a cross-catalog code-list binding —
 * the case that exercises both the destructive-wipe ordering (the attribute→code-list FK is
 * `ON DELETE RESTRICT`) and the dependency-ordered re-import (the `main` catalog must import
 * after the `shared` catalog whose code list it binds).
 */
class RestoreTenantSnapshotIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var jdbi: Jdbi

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
        withMediator {
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

    @Test
    fun `restore keeps the identity a moved resource carries`() {
        val tenant = createTenant("Restore Identity")
        val tenantId = TenantId(tenant.id)
        val origin = CatalogKey.of("origin")
        val destination = CatalogKey.of("destination")
        val themeAddress = ResourceAddress(CatalogResourceType.THEME, origin.value, "brand")

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = origin, name = "Origin").execute()
            CreateCatalog(tenantKey = tenant.id, id = destination, name = "Destination").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(origin, tenantId)), name = "Brand").execute()
        }

        // Move it, so its identity no longer matches anything its address could be re-derived from.
        withMediator {
            val preview = PreviewCatalogResourceMove(tenant.id, listOf(themeAddress.movedTo(destination))).query()
            MoveCatalogResources(tenant.id, listOf(themeAddress.movedTo(destination)), preview.planFingerprint).execute()
        }

        val identityBefore = themeIdentity(tenant.id.value, "destination", "brand")

        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }
        withMediator { RestoreTenantSnapshot(tenant.id, snapshot.bytes).execute() }

        assertThat(themeIdentity(tenant.id.value, "destination", "brand"))
            .describedAs("a restored resource keeps its identity, so generation history still joins to it")
            .isEqualTo(identityBefore)
    }

    /**
     * Snapshots taken by development builds from before relocation stopped leaving aliases carry an
     * `aliases` list in `identities.json`. Nothing reads it any more; such a snapshot must still
     * restore, with its identities, rather than be refused over a field it no longer needs.
     */
    @Test
    fun `a snapshot that still records aliases restores, ignoring them`() {
        val tenant = createTenant("Restore Legacy Aliases")
        val catalog = CatalogKey.of("brand")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalog, name = "Brand").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(catalog, TenantId(tenant.id))), name = "Brand").execute()
        }
        val identityBefore = themeIdentity(tenant.id.value, catalog.value, "brand")
        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }

        val withAliases = rewriteEntry(snapshot.bytes, "identities.json") { json ->
            val identities = objectMapper.readTree(json) as tools.jackson.databind.node.ObjectNode
            identities.putArray("aliases").addObject()
                .put("type", "theme")
                .put("catalogKey", "retired")
                .put("key", "brand")
                .put("targetResourceId", identityBefore.toString())
            objectMapper.writeValueAsBytes(identities)
        }
        withMediator { RestoreTenantSnapshot(tenant.id, withAliases).execute() }

        assertThat(themeIdentity(tenant.id.value, catalog.value, "brand")).isEqualTo(identityBefore)
    }

    private fun rewriteEntry(archive: ByteArray, name: String, transform: (ByteArray) -> ByteArray): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        check(name in entries) { "$name not found" }
        entries[name] = transform(entries.getValue(name))
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (entryName, bytes) ->
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun themeIdentity(tenantKey: String, catalogKey: String, key: String): UUID = jdbi.withHandle<UUID, Exception> { handle ->
        handle
            .createQuery("SELECT resource_id FROM themes WHERE tenant_key = :t AND catalog_key = :c AND id = :k")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("k", key)
            .mapTo(UUID::class.java)
            .one()
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
