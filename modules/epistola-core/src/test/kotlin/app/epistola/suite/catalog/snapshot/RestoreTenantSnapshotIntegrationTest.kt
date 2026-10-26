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
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.queries.GetCatalogReleaseDetail
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
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
import app.epistola.suite.testing.TestPrincipalUser
import app.epistola.suite.testing.testAddress
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
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
        val regionAttr = AttributeId(AttributeKey.of("region"), testCatalog(tenantId, main))
        val regionsCodeList = CodeListId(CodeListKey.of("regions"), testCatalog(tenantId, shared))

        val brandTheme = ThemeId(ThemeKey.of("brand"), testCatalog(tenantId, main))

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
            declareDependency(tenant.id, main, on = shared)
            CreateAttributeDefinition(
                id = regionAttr,
                displayName = "Region",
                codeListId = regionsCodeList,
            ).execute()
            CreateTheme(id = brandTheme, name = "Brand").execute()
            SetTenantDefaultTheme(tenantId = tenant.id, themeId = ThemeKey.of("brand"), catalog = testCatalog(tenant.id, main)).execute()
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
            assertThat(GetCatalog(testCatalog(tenant.id, SYSTEM_CATALOG_KEY)).query()).isNotNull()

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

            // A restored authored catalog has a draft to keep working in.
            assertThat(ListCatalogReleases(testCatalog(tenant.id, main)).query().map { it.label to it.status })
                .containsExactly("Next release" to CatalogReleaseStatus.DRAFT)

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
        val themeAddress = testAddress(tenant.id, CatalogResourceType.THEME, origin.value, "brand")

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = origin, name = "Origin").execute()
            CreateCatalog(tenantKey = tenant.id, id = destination, name = "Destination").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(tenantId, origin)), name = "Brand").execute()
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
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), catalog)), name = "Brand").execute()
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

    /**
     * Snapshots still say `SUBSCRIBED` or `AUTHORED` per catalog, a format the hub shares, but catalogs
     * have no type (ADR 0032): a restore fills every catalog's next version alike, as it was.
     */
    @Test
    fun `a snapshot's catalog type is ignored, and every catalog's next version is filled as it was`() {
        val tenant = createTenant("Restore Ignores Type")
        val catalog = CatalogKey.of("brand")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalog, name = "Brand").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), catalog)), name = "Brand").execute()
        }
        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }
        assertThat(readManifest(snapshot.bytes).catalogs.single { it.catalogKey == "brand" }.type)
            .`as`("a catalog without a source is written as AUTHORED, for the hub")
            .isEqualTo("AUTHORED")

        val typedSubscribed = rewriteEntry(snapshot.bytes, "snapshot.json") { json ->
            val manifest = objectMapper.readTree(json) as tools.jackson.databind.node.ObjectNode
            manifest.get("catalogs").forEach { (it as tools.jackson.databind.node.ObjectNode).put("type", "SUBSCRIBED") }
            objectMapper.writeValueAsBytes(manifest)
        }
        withMediator { RestoreTenantSnapshot(tenant.id, typedSubscribed).execute() }

        withMediator {
            val restored = GetCatalog(testCatalog(tenant.id, catalog)).query()!!
            assertThat(restored.installedReleaseVersion).`as`("not installed as a received release").isNull()
            assertThat(ListCatalogReleaseSummaries(testCatalog(tenant.id, catalog)).query()).isEmpty()
            assertThat(GetCatalogEditability(testCatalog(tenant.id, catalog)).query().editable).isTrue()
        }
        themeIdentity(tenant.id.value, catalog.value, "brand")
    }

    @Test
    fun `a snapshot writes SUBSCRIBED for a catalog with a source, for the hub`() {
        val tenant = createTenant("Snapshot Source Type")
        withMediator {
            app.epistola.suite.catalog.commands.RegisterCatalog(tenantKey = tenant.id, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json").execute()
        }
        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }
        assertThat(readManifest(snapshot.bytes).catalogs.single { it.catalogKey == "epistola-demo" }.type).isEqualTo("SUBSCRIBED")
    }

    @Test
    fun `a restored catalog keeps its releases, who released them, and the release its next release is on`() {
        val tenant = createTenant("Restore Keeps Releases")
        val catalog = CatalogKey.of("released")
        val theme = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), catalog))
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalog, name = "Released").execute()
            CreateTheme(id = theme, name = "Brand 1").execute()
            MarkCatalogReady(testCatalog(tenant.id, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant.id, catalog), version = "1.0.0").execute()
            UpdateTheme(theme, name = "Brand 2").execute()
            MarkCatalogReady(testCatalog(tenant.id, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant.id, catalog), version = "1.1.0").execute()
            UpdateTheme(theme, name = "Brand 3, not released").execute()
        }
        val before = withMediator { releasesOf(tenant.id, catalog) }
        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }

        withMediator { RestoreTenantSnapshot(tenant.id, snapshot.bytes).execute() }

        withMediator {
            assertThat(releasesOf(tenant.id, catalog)).`as`("number, id, fingerprint and base of each").isEqualTo(before)
            val next = ListCatalogReleases(testCatalog(tenant.id, catalog)).query().single { it.isNext }
            assertThat(next.base).`as`("the next release is on the release it was on").isEqualTo("1.1.0")
            assertThat(GetCatalog(testCatalog(tenant.id, catalog)).query()!!.releasedVersion).isEqualTo("1.1.0")
            assertThat(GetTheme(theme).query()!!.name).`as`("the working copy as it was").isEqualTo("Brand 3, not released")
            assertThat(GetCatalogReleaseDetail(testCatalog(tenant.id, catalog), "1.0.0").query()!!.releasedByLine).isEqualTo("Released here by ${TestPrincipalUser.DISPLAY_NAME}")
            // The releases kept their content: they export, and the next one is cut on 1.1.0.
            assertThat(ExportCatalogZip(testCatalog(tenant.id, catalog), "1.0.0").execute().zipBytes).isNotEmpty()
            MarkCatalogReady(testCatalog(tenant.id, catalog)).execute()
            assertThat(ReleaseCatalogDraft(catalog = testCatalog(tenant.id, catalog), version = "1.2.0").execute().version).isEqualTo("1.2.0")
        }
    }

    @Test
    fun `a restored catalog keeps where it receives releases from`() {
        val tenant = createTenant("Restore Keeps Source")
        val source = "classpath:epistola/catalogs/fixture/catalog.json"
        val catalog = CatalogKey.of("epistola-demo")
        withMediator { app.epistola.suite.catalog.commands.RegisterCatalog(tenantKey = tenant.id, sourceUrl = source).execute() }
        val before = withMediator { GetCatalog(testCatalog(tenant.id, catalog)).query()!! }
        val releases = withMediator { releasesOf(tenant.id, catalog) }
        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }

        withMediator { RestoreTenantSnapshot(tenant.id, snapshot.bytes).execute() }

        withMediator {
            val restored = GetCatalog(testCatalog(tenant.id, catalog)).query()!!
            assertThat(restored.sourceUrl).isEqualTo(source)
            assertThat(restored.sourceAuthType).isEqualTo(before.sourceAuthType)
            assertThat(restored.installedReleaseVersion).isEqualTo(before.installedReleaseVersion)
            assertThat(releasesOf(tenant.id, catalog)).isEqualTo(releases)
        }
    }

    @Test
    fun `a snapshot from before it carried sources and releases still restores, with neither`() {
        val tenant = createTenant("Restore Schema Two")
        val catalog = CatalogKey.of("older")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalog, name = "Older").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), catalog)), name = "Brand").execute()
            MarkCatalogReady(testCatalog(tenant.id, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant.id, catalog), version = "1.0.0").execute()
        }
        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }
        val schemaTwo = rewriteEntry(snapshot.bytes, "snapshot.json") { json ->
            val manifest = objectMapper.readTree(json) as tools.jackson.databind.node.ObjectNode
            manifest.put("schemaVersion", 2)
            manifest.get("catalogs").forEach { (it as tools.jackson.databind.node.ObjectNode).remove(listOf("source", "installed", "nextBase", "releases")) }
            objectMapper.writeValueAsBytes(manifest)
        }

        withMediator { RestoreTenantSnapshot(tenant.id, schemaTwo).execute() }

        withMediator {
            assertThat(ListCatalogReleaseSummaries(testCatalog(tenant.id, catalog)).query()).isEmpty()
            assertThat(GetCatalog(testCatalog(tenant.id, catalog)).query()!!.sourceUrl).isNull()
        }
    }

    private data class RestoredRelease(val version: String, val releaseId: String, val fingerprint: String, val baseVersion: String?, val baseFingerprint: String?)

    private fun releasesOf(tenant: app.epistola.suite.common.ids.TenantKey, catalog: CatalogKey): List<RestoredRelease> = ListCatalogReleases(testCatalog(tenant, catalog)).query()
        .filter { it.status == CatalogReleaseStatus.RELEASED }
        .map { RestoredRelease(it.version!!, it.versionId, it.fingerprint!!, it.base, null) }
        .sortedBy { it.version }
        .zip(baseFingerprints(tenant, catalog)) { release, fingerprint -> release.copy(baseFingerprint = fingerprint) }

    private fun baseFingerprints(tenant: app.epistola.suite.common.ids.TenantKey, catalog: CatalogKey): List<String?> = jdbi.withHandle<List<String?>, Exception> { handle ->
        handle.createQuery("SELECT base_fingerprint FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} ORDER BY version")
            .bind("t", tenant).bindCatalog("c", testCatalog(tenant, catalog))
            .map { rs, _ -> rs.getString(1) }
            .list()
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
            .createQuery("SELECT resource_id FROM current_themes WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = :k")
            .bind("t", tenantKey)
            .bindCatalog("c", testCatalog(tenantKey, catalogKey))
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
