package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.CheckCatalogUpgrade
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Versioning semantics of catalog **ZIP exchange** — distinct from
 * `ExportUsesReleasedVersionTest` (export version *label* by release state) and
 * `RepeatedCatalogDeploymentTest` (schema preservation across re-imports). Pins:
 *
 *  - re-import is an in-place **merge**, NOT a stale-pruning upgrade
 *    (contrast with `UpgradeCatalog`);
 *  - the content fingerprint is stable across a full ZIP round-trip;
 *  - a ZIP-imported catalog carries no SUBSCRIBED upgrade state and is not an
 *    upgrade target (`UpgradeCatalog`/preview don't apply — re-import is the
 *    only "upgrade from a ZIP");
 *  - release identity: a ZIP import that **creates** an AUTHORED catalog
 *    adopts the manifest's clean released SemVer as the *initial* release;
 *    a `-dev`/never-released manifest stays unreleased; and importing into an
 *    **existing** AUTHORED catalog never fabricates or changes release state
 *    (it's an edit — the owner releases deliberately).
 */
class CatalogZipVersioningTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var fingerprintService: CatalogFingerprintService

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name] = zip.readAllBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    private fun rezip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun manifestOf(zipBytes: ByteArray): CatalogManifest = objectMapper.readValue(unzip(zipBytes)["catalog.json"]!!, CatalogManifest::class.java)

    private fun themeName(tenantKey: app.epistola.suite.common.ids.TenantKey, catalogKey: CatalogKey, slug: String): String? = jdbi.withHandle<String?, Exception> { h ->
        h.createQuery("SELECT name FROM themes WHERE tenant_key = :t AND catalog_key = :c AND id = :s")
            .bind("t", tenantKey).bind("c", catalogKey).bind("s", slug)
            .mapTo(String::class.java).findOne().orElse(null)
    }

    @Test
    fun `re-importing a ZIP that dropped a resource updates in place but does NOT prune the dropped resource`() {
        val tenant = createTenant("Zip Merge Semantics")
        val tenantKey = tenant.id
        val catalogKey = CatalogKey.of("zipver-merge")
        val catalogId = CatalogId(catalogKey, TenantId(tenantKey))

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Zip Merge").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("keep"), catalogId), name = "Keep V1").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("drop"), catalogId), name = "Drop Me").execute()

            val zip1 = ExportCatalogZip(tenantKey = tenantKey, catalogKey = catalogKey).execute().zipBytes

            // Build the "next release" ZIP: drop the `drop` theme entirely and
            // rename `keep` (a publisher edit).
            val entries = unzip(zip1)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            val kept = objectMapper.createArrayNode()
            (manifest.get("resources") as ArrayNode).forEach { r ->
                if (r.get("slug").asString() != "drop") kept.add(r)
            }
            manifest.set("resources", kept)
            entries.remove("resources/theme/drop.json")
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
            val keepDetail = objectMapper.readTree(entries["resources/theme/keep.json"]!!) as ObjectNode
            (keepDetail.get("resource") as ObjectNode).put("name", "Keep V2")
            entries["resources/theme/keep.json"] = objectMapper.writeValueAsBytes(keepDetail)

            val result = ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = rezip(entries),
                catalogType = CatalogType.AUTHORED,
            ).execute()
            assertThat(result.results).noneMatch { it.status == InstallStatus.FAILED }

            // `keep` was updated in place…
            assertThat(themeName(tenantKey, catalogKey, "keep")).isEqualTo("Keep V2")
            // …but `drop`, absent from the new ZIP, is NOT pruned. ZIP re-import
            // is a best-effort in-place merge — only the SUBSCRIBED
            // `UpgradeCatalog` path removes stale resources.
            assertThat(themeName(tenantKey, catalogKey, "drop")).isEqualTo("Drop Me")
        }
    }

    @Test
    fun `content fingerprint is stable across a full ZIP export-import-export round-trip`() {
        val src = createTenant("Zip FP Src")
        val dst = createTenant("Zip FP Dst")
        val catalogKey = CatalogKey.of("zipver-fp")

        withMediator {
            CreateCatalog(tenantKey = src.id, id = catalogKey, name = "Zip FP").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(catalogKey, TenantId(src.id))), name = "Brand").execute()

            val zip1 = ExportCatalogZip(tenantKey = src.id, catalogKey = catalogKey).execute().zipBytes
            val fpExport = fingerprintService.fingerprint(src.id, catalogKey)
            // The manifest stamps the fingerprint of the exact exported bytes.
            assertThat(manifestOf(zip1).release.fingerprint).isEqualTo(fpExport)

            // Import into a *different* tenant, then re-export.
            val imported = ImportCatalogZip(
                tenantKey = dst.id,
                zipBytes = zip1,
                catalogType = CatalogType.AUTHORED,
            ).execute()
            assertThat(imported.results).noneMatch { it.status == InstallStatus.FAILED }

            val fpReimport = fingerprintService.fingerprint(dst.id, catalogKey)
            val zip2 = ExportCatalogZip(tenantKey = dst.id, catalogKey = catalogKey).execute().zipBytes

            // Same content ⇒ identical fingerprint, end to end and tenant-independent.
            assertThat(fpReimport).isEqualTo(fpExport)
            assertThat(manifestOf(zip2).release.fingerprint).isEqualTo(fpExport)
        }
    }

    @Test
    fun `a ZIP-imported catalog has no installed version state and is not a subscriber upgrade target`() {
        val tenant = createTenant("Zip No Installed State")
        val catalogKey = CatalogKey.of("zipver-authored")

        withMediator {
            val src = createTenant("Zip Source")
            CreateCatalog(tenantKey = src.id, id = catalogKey, name = "Zip Authored").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("tee"), CatalogId(catalogKey, TenantId(src.id))), name = "T").execute()
            val zip = ExportCatalogZip(tenantKey = src.id, catalogKey = catalogKey).execute().zipBytes

            ImportCatalogZip(tenantKey = tenant.id, zipBytes = zip, catalogType = CatalogType.AUTHORED).execute()

            val catalog = GetCatalog(tenant.id, catalogKey).query()!!
            // ZIP import creates an AUTHORED catalog: no SUBSCRIBED install state,
            // no source URL. "Upgrade from a ZIP" = re-import, never the
            // SUBSCRIBED UpgradeCatalog/preview path.
            assertThat(catalog.type).isEqualTo(CatalogType.AUTHORED)
            assertThat(catalog.sourceUrl).isNull()
            assertThat(catalog.installedReleaseVersion).isNull()
            assertThat(catalog.installedFingerprint).isNull()
            assertThat(catalog.installedResourceFingerprints).isNull()

            assertThatThrownBy { PreviewCatalogUpgrade(tenant.id, catalogKey).query() }
                .hasMessageContaining("only subscribed catalogs can be upgraded")
            assertThatThrownBy { CheckCatalogUpgrade(tenant.id, catalogKey).query() }
                .hasMessageContaining("only subscribed catalogs can be upgraded")
        }
    }

    @Test
    fun `importing a released ZIP that creates a new AUTHORED catalog adopts it as the initial release`() {
        val publisher = createTenant("Zip Rel Publisher")
        val consumer = createTenant("Zip Rel Consumer")
        val catalogKey = CatalogKey.of("zipver-adopt")

        withMediator {
            CreateCatalog(tenantKey = publisher.id, id = catalogKey, name = "Zip Rel").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("thm"), CatalogId(catalogKey, TenantId(publisher.id))), name = "Th").execute()
            ReleaseCatalogVersion(tenantKey = publisher.id, catalogKey = catalogKey, version = "1.0.0").execute()
            val publishedZip = ExportCatalogZip(tenantKey = publisher.id, catalogKey = catalogKey).execute().zipBytes
            assertThat(manifestOf(publishedZip).release.version).isEqualTo("1.0.0")

            // Consumer has no such catalog → the import *creates* it, so the
            // published 1.0.0 becomes its initial release (no authorship
            // history to protect; round-trip fingerprint is deterministic, so
            // the release row is real and consistent — not fabricated).
            ImportCatalogZip(tenantKey = consumer.id, zipBytes = publishedZip, catalogType = CatalogType.AUTHORED).execute()

            assertThat(GetCatalog(consumer.id, catalogKey).query()!!.releasedVersion).isEqualTo("1.0.0")
            val reexport = ExportCatalogZip(tenantKey = consumer.id, catalogKey = catalogKey).execute().zipBytes
            // Clean version (no `-dev`): the working copy matches the adopted release.
            assertThat(manifestOf(reexport).release.version).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `importing a never-released ZIP creates an unreleased catalog`() {
        val publisher = createTenant("Zip Unrel Publisher")
        val consumer = createTenant("Zip Unrel Consumer")
        val catalogKey = CatalogKey.of("zipver-unrel")

        withMediator {
            CreateCatalog(tenantKey = publisher.id, id = catalogKey, name = "Zip Unrel").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("thm"), CatalogId(catalogKey, TenantId(publisher.id))), name = "Th").execute()
            // Publisher never cut a release → manifest carries 0.0.0-dev.
            val devZip = ExportCatalogZip(tenantKey = publisher.id, catalogKey = catalogKey).execute().zipBytes
            assertThat(manifestOf(devZip).release.version).isEqualTo("0.0.0-dev")

            // A `-dev` label is not a real release: nothing to adopt.
            ImportCatalogZip(tenantKey = consumer.id, zipBytes = devZip, catalogType = CatalogType.AUTHORED).execute()
            assertThat(GetCatalog(consumer.id, catalogKey).query()!!.releasedVersion).isNull()
        }
    }

    @Test
    fun `importing into an existing AUTHORED catalog does not fabricate or change release state`() {
        val publisher = createTenant("Zip Exist Publisher")
        val consumer = createTenant("Zip Exist Consumer")
        val catalogKey = CatalogKey.of("zipver-exist")

        withMediator {
            // Publisher publishes 2.0.0.
            CreateCatalog(tenantKey = publisher.id, id = catalogKey, name = "Zip Exist").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("thm"), CatalogId(catalogKey, TenantId(publisher.id))), name = "Pub Th").execute()
            ReleaseCatalogVersion(tenantKey = publisher.id, catalogKey = catalogKey, version = "2.0.0").execute()
            val publishedZip = ExportCatalogZip(tenantKey = publisher.id, catalogKey = catalogKey).execute().zipBytes

            // Consumer already AUTHORS a catalog with the same slug, never released.
            CreateCatalog(tenantKey = consumer.id, id = catalogKey, name = "Consumer's Own").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("own"), CatalogId(catalogKey, TenantId(consumer.id))), name = "Own Th").execute()

            // Importing into the *existing* catalog is an edit, not a release
            // act — the publisher's 2.0.0 is NOT adopted; the owner's release
            // history (here: none) is untouched. It shows as drift, correctly.
            ImportCatalogZip(tenantKey = consumer.id, zipBytes = publishedZip, catalogType = CatalogType.AUTHORED).execute()

            assertThat(GetCatalog(consumer.id, catalogKey).query()!!.releasedVersion).isNull()
            val reexport = ExportCatalogZip(tenantKey = consumer.id, catalogKey = catalogKey).execute().zipBytes
            assertThat(manifestOf(reexport).release.version).isEqualTo("0.0.0-dev")
        }
    }
}
