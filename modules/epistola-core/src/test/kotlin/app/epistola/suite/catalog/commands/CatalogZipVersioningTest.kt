package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CheckCatalogUpgrade
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
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
    fun `importing a ZIP with an older catalog schema version is rejected`() {
        val pub = createTenant("Zip Old Schema Pub")
        val consumer = createTenant("Zip Old Schema Consumer")
        val catalogKey = CatalogKey.of("zipver-oldschema")

        withMediator {
            CreateCatalog(tenantKey = pub.id, id = catalogKey, name = "Zip Old Schema").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("thm"), CatalogId(catalogKey, TenantId(pub.id))), name = "Th").execute()
            val zip = ExportCatalogZip(tenantKey = pub.id, catalogKey = catalogKey).execute().zipBytes

            // Rewrite the manifest to an older catalog schema version (a publisher
            // on an older Epistola). A source-less ZIP can't be re-fetched and we
            // don't migrate stored content in place, so the import is rejected
            // outright — the publisher must re-export from a current source.
            val entries = unzip(zip)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            manifest.put("schemaVersion", 2)
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            assertThatThrownBy {
                ImportCatalogZip(tenantKey = consumer.id, zipBytes = rezip(entries), catalogType = CatalogType.AUTHORED).execute()
            }.isInstanceOf(CatalogSchemaTooOldException::class.java)
                .hasMessageContaining("predates the oldest supported version")

            // Nothing was created.
            assertThat(GetCatalog(consumer.id, catalogKey).query()).isNull()
        }
    }

    @Test
    fun `importing an older-schema ZIP as SUBSCRIBED is rejected — subscribed is never migrated`() {
        val pub = createTenant("Zip Old Sub Pub")
        val consumer = createTenant("Zip Old Sub Consumer")
        val catalogKey = CatalogKey.of("zipver-oldsub")

        withMediator {
            CreateCatalog(tenantKey = pub.id, id = catalogKey, name = "Zip Old Sub").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("thm"), CatalogId(catalogKey, TenantId(pub.id))), name = "Th").execute()
            val zip = ExportCatalogZip(tenantKey = pub.id, catalogKey = catalogKey).execute().zipBytes
            val entries = unzip(zip)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            manifest.put("schemaVersion", 2)
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            // A subscribed mirror is never migrated locally — the source must
            // republish — so an older-schema SUBSCRIBED ZIP is blocked outright.
            assertThatThrownBy {
                ImportCatalogZip(tenantKey = consumer.id, zipBytes = rezip(entries), catalogType = CatalogType.SUBSCRIBED).execute()
            }.isInstanceOf(CatalogSchemaTooOldException::class.java)

            assertThat(GetCatalog(consumer.id, catalogKey).query()).isNull()
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

    // ── SUBSCRIBED: the ZIP is the upgrade transport (UpgradeCatalog parity) ──

    /** Publisher-side helper: an AUTHORED catalog with the given themes, released. */
    private fun publishZip(publisherTenant: app.epistola.suite.common.ids.TenantKey, slug: String, version: String, themes: Map<String, String>): ByteArray {
        val catalogKey = CatalogKey.of(slug)
        CreateCatalog(tenantKey = publisherTenant, id = catalogKey, name = slug).execute()
        themes.forEach { (s, n) -> CreateTheme(id = ThemeId(ThemeKey.of(s), CatalogId(catalogKey, TenantId(publisherTenant))), name = n).execute() }
        ReleaseCatalogVersion(tenantKey = publisherTenant, catalogKey = catalogKey, version = version).execute()
        return ExportCatalogZip(tenantKey = publisherTenant, catalogKey = catalogKey).execute().zipBytes
    }

    @Test
    fun `importing a ZIP as SUBSCRIBED records installed state from the manifest (managed mirror)`() {
        val pub = createTenant("Sub Mirror Pub")
        val sub = createTenant("Sub Mirror Sub")
        val catalogKey = CatalogKey.of("zipsub-mirror")

        withMediator {
            val zip = publishZip(pub.id, "zipsub-mirror", "1.0.0", mapOf("brand" to "Brand"))
            val manifestFp = manifestOf(zip).release.fingerprint

            ImportCatalogZip(tenantKey = sub.id, zipBytes = zip, catalogType = CatalogType.SUBSCRIBED).execute()

            val catalog = GetCatalog(sub.id, catalogKey).query()!!
            assertThat(catalog.type).isEqualTo(CatalogType.SUBSCRIBED)
            assertThat(catalog.sourceUrl).isNull() // ZIP-sourced, no URL
            assertThat(catalog.installedReleaseVersion).isEqualTo("1.0.0")
            assertThat(catalog.installedFingerprint).isEqualTo(manifestFp)
            assertThat(catalog.installedResourceFingerprints).isNotNull().containsKey("theme/brand")
        }
    }

    @Test
    fun `re-importing a SUBSCRIBED ZIP prunes resources the publisher dropped and advances the version`() {
        val pub = createTenant("Sub Prune Pub")
        val sub = createTenant("Sub Prune Sub")
        val catalogKey = CatalogKey.of("zipsub-prune")

        withMediator {
            val v1 = publishZip(pub.id, "zipsub-prune", "1.0.0", mapOf("keep" to "Keep", "drop" to "Drop"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = v1, catalogType = CatalogType.SUBSCRIBED).execute()
            assertThat(themeName(sub.id, catalogKey, "drop")).isEqualTo("Drop")

            // Publisher's next release drops `drop`.
            val entries = unzip(v1)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            val kept = objectMapper.createArrayNode()
            (manifest.get("resources") as ArrayNode).forEach { if (it.get("slug").asString() != "drop") kept.add(it) }
            manifest.set("resources", kept)
            (manifest.get("release") as ObjectNode).put("version", "2.0.0")
            entries.remove("resources/theme/drop.json")
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            ImportCatalogZip(tenantKey = sub.id, zipBytes = rezip(entries), catalogType = CatalogType.SUBSCRIBED).execute()

            // Unlike AUTHORED re-import, SUBSCRIBED re-import prunes stale (full
            // UpgradeCatalog parity) and advances the installed version.
            assertThat(themeName(sub.id, catalogKey, "drop")).isNull()
            assertThat(themeName(sub.id, catalogKey, "keep")).isEqualTo("Keep")
            assertThat(GetCatalog(sub.id, catalogKey).query()!!.installedReleaseVersion).isEqualTo("2.0.0")
        }
    }

    @Test
    fun `a cross-catalog conflict blocks a SUBSCRIBED ZIP upgrade and changes nothing`() {
        val pub = createTenant("Sub Conflict Pub")
        val sub = createTenant("Sub Conflict Sub")
        val catalogKey = CatalogKey.of("zipsub-conflict")

        withMediator {
            val v1 = publishZip(pub.id, "zipsub-conflict", "1.0.0", mapOf("pinned" to "Pinned"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = v1, catalogType = CatalogType.SUBSCRIBED).execute()

            // A template in another catalog pins the subscribed theme.
            val tplId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(CatalogKey.DEFAULT, TenantId(sub.id)))
            CreateDocumentTemplate(id = tplId, name = "Cross-Ref Template").execute()
            UpdateDocumentTemplate(id = tplId, themeId = ThemeKey.of("pinned"), themeCatalogKey = catalogKey).execute()

            // Publisher drops the still-referenced theme in the next release.
            val entries = unzip(v1)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            manifest.set("resources", objectMapper.createArrayNode())
            (manifest.get("release") as ObjectNode).put("version", "2.0.0")
            entries.remove("resources/theme/pinned.json")
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            assertThatThrownBy {
                ImportCatalogZip(tenantKey = sub.id, zipBytes = rezip(entries), catalogType = CatalogType.SUBSCRIBED).execute()
            }.isInstanceOf(CatalogUpgradeConflictException::class.java).hasMessageContaining("pinned")

            // Conflict thrown before any mutation: version + theme unchanged.
            assertThat(GetCatalog(sub.id, catalogKey).query()!!.installedReleaseVersion).isEqualTo("1.0.0")
            assertThat(themeName(sub.id, catalogKey, "pinned")).isEqualTo("Pinned")
        }
    }

    @Test
    fun `a failed resource aborts a SUBSCRIBED ZIP upgrade — installed version unchanged, stale not pruned`() {
        val pub = createTenant("Sub Abort Pub")
        val sub = createTenant("Sub Abort Sub")
        val catalogKey = CatalogKey.of("zipsub-abort")

        withMediator {
            val v1 = publishZip(pub.id, "zipsub-abort", "1.0.0", mapOf("keep" to "Keep", "drop" to "Drop"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = v1, catalogType = CatalogType.SUBSCRIBED).execute()

            // Next "release": drop `drop` (would be pruned on success) and bump
            // to 2.0.0 — but corrupt `keep` by removing its detail file so its
            // re-install FAILS.
            val entries = unzip(v1)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            val kept = objectMapper.createArrayNode()
            (manifest.get("resources") as ArrayNode).forEach { if (it.get("slug").asString() != "drop") kept.add(it) }
            manifest.set("resources", kept)
            (manifest.get("release") as ObjectNode).put("version", "2.0.0")
            entries.remove("resources/theme/drop.json")
            entries.remove("resources/theme/keep.json") // referenced by manifest → install FAILS
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            val result = ImportCatalogZip(tenantKey = sub.id, zipBytes = rezip(entries), catalogType = CatalogType.SUBSCRIBED).execute()

            assertThat(result.aborted).isTrue()
            assertThat(result.results).anyMatch { it.status == InstallStatus.FAILED }
            // Aborted: version not advanced, stale `drop` NOT pruned (retry-able).
            assertThat(GetCatalog(sub.id, catalogKey).query()!!.installedReleaseVersion).isEqualTo("1.0.0")
            assertThat(themeName(sub.id, catalogKey, "drop")).isEqualTo("Drop")
        }
    }

    @Test
    fun `importing over a catalog of a different type is rejected`() {
        val pub = createTenant("Sub Mismatch Pub")
        val sub = createTenant("Sub Mismatch Sub")
        val catalogKey = CatalogKey.of("zipsub-mismatch")

        withMediator {
            val zip = publishZip(pub.id, "zipsub-mismatch", "1.0.0", mapOf("thm" to "Th"))

            // Exists as SUBSCRIBED → importing as AUTHORED is rejected.
            ImportCatalogZip(tenantKey = sub.id, zipBytes = zip, catalogType = CatalogType.SUBSCRIBED).execute()
            assertThatThrownBy {
                ImportCatalogZip(tenantKey = sub.id, zipBytes = zip, catalogType = CatalogType.AUTHORED).execute()
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("already exists as SUBSCRIBED")

            // And the reverse: an AUTHORED catalog can't be imported as SUBSCRIBED.
            val pub2 = createTenant("Sub Mismatch Pub2")
            val zip2 = publishZip(pub2.id, "zipsub-mismatch2", "1.0.0", mapOf("thm" to "Th"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = zip2, catalogType = CatalogType.AUTHORED).execute()
            assertThatThrownBy {
                ImportCatalogZip(tenantKey = sub.id, zipBytes = zip2, catalogType = CatalogType.SUBSCRIBED).execute()
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("already exists as AUTHORED")
        }
    }

    @Test
    fun `browsing a ZIP-subscribed catalog lists installed resources instead of erroring (no source URL)`() {
        val pub = createTenant("Sub Browse Pub")
        val sub = createTenant("Sub Browse Sub")
        val catalogKey = CatalogKey.of("zipsub-browse")

        withMediator {
            val zip = publishZip(pub.id, "zipsub-browse", "1.0.0", mapOf("brand" to "Brand", "alt" to "Alt"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = zip, catalogType = CatalogType.SUBSCRIBED).execute()

            // Regression: a ZIP-subscribed catalog has no source URL — browsing
            // must NOT throw "Subscribed catalog has no source URL"; it lists
            // the locally-installed resources.
            val result = BrowseCatalog(sub.id, catalogKey).query()

            assertThat(result.catalog.type).isEqualTo(CatalogType.SUBSCRIBED)
            assertThat(result.resources).extracting<String> { it.slug }
                .contains("brand", "alt")
            assertThat(result.resources).allMatch { it.status.name == "INSTALLED" }
        }
    }

    @Test
    fun `browse reports an installed code list as INSTALLED, not Available (codeList + font included)`() {
        val pub = createTenant("CL Browse Pub")
        val sub = createTenant("CL Browse Sub")
        val catalogKey = CatalogKey.of("zipsub-codelist")

        withMediator {
            CreateCatalog(tenantKey = pub.id, id = catalogKey, name = "CL Browse").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(catalogKey, TenantId(pub.id))), name = "Brand").execute()
            CreateCodeList(
                id = CodeListId(CodeListKey.of("bcp-47"), CatalogId(catalogKey, TenantId(pub.id))),
                displayName = "BCP-47 Language Tags",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English"), CodeListEntry("nl", "Dutch")),
            ).execute()
            ReleaseCatalogVersion(tenantKey = pub.id, catalogKey = catalogKey, version = "1.0.0").execute()
            val zip = ExportCatalogZip(tenantKey = pub.id, catalogKey = catalogKey).execute().zipBytes

            ImportCatalogZip(tenantKey = sub.id, zipBytes = zip, catalogType = CatalogType.SUBSCRIBED).execute()

            // Regression: BrowseCatalog's installed-detection union omitted
            // code_lists (and fonts), so an installed code list showed as
            // "Available" (the BCP-47-in-system bug).
            val codeList = BrowseCatalog(sub.id, catalogKey).query().resources
                .single { it.type == "codeList" && it.slug == "bcp-47" }
            assertThat(codeList.status.name).isEqualTo("INSTALLED")
            assertThat(codeList.name).isEqualTo("BCP-47 Language Tags")
        }
    }

    // ── AUTHORED re-import: MERGE (default) vs REPLACE ────────────────────────

    @Test
    fun `AUTHORED re-import with REPLACE prunes local-only resources (release untouched)`() {
        val tenant = createTenant("Authored Replace")
        val catalogKey = CatalogKey.of("authored-replace")
        val catalogId = CatalogId(catalogKey, TenantId(tenant.id))

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Authored Replace").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("keep"), catalogId), name = "Keep").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("local-only"), catalogId), name = "Local Only").execute()

            // Build a "next" ZIP without `local-only`.
            val entries = unzip(ExportCatalogZip(tenantKey = tenant.id, catalogKey = catalogKey).execute().zipBytes)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            val kept = objectMapper.createArrayNode()
            (manifest.get("resources") as ArrayNode).forEach { if (it.get("slug").asString() != "local-only") kept.add(it) }
            manifest.set("resources", kept)
            entries.remove("resources/theme/local-only.json")
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            ImportCatalogZip(
                tenantKey = tenant.id,
                zipBytes = rezip(entries),
                catalogType = CatalogType.AUTHORED,
                authoredMode = AuthoredImportMode.REPLACE,
            ).execute()

            // REPLACE mirrors the ZIP: local-only theme is pruned, kept theme stays.
            // (Contrast `MERGE` which keeps local-only — covered separately.)
            assertThat(themeName(tenant.id, catalogKey, "local-only")).isNull()
            assertThat(themeName(tenant.id, catalogKey, "keep")).isEqualTo("Keep")
            // Release state untouched — an edit, never an authorship act.
            assertThat(GetCatalog(tenant.id, catalogKey).query()!!.releasedVersion).isNull()
        }
    }

    @Test
    fun `AUTHORED REPLACE blocks on a cross-catalog conflict and changes nothing`() {
        val tenant = createTenant("Authored Replace Conflict")
        val catalogKey = CatalogKey.of("authored-replace-conflict")
        val catalogId = CatalogId(catalogKey, TenantId(tenant.id))

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Authored Replace Conflict").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("pinned"), catalogId), name = "Pinned").execute()

            // A template in another catalog pins the theme REPLACE would prune.
            val tplId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(CatalogKey.DEFAULT, TenantId(tenant.id)))
            CreateDocumentTemplate(id = tplId, name = "Cross-Ref Template").execute()
            UpdateDocumentTemplate(id = tplId, themeId = ThemeKey.of("pinned"), themeCatalogKey = catalogKey).execute()

            // Next ZIP drops the still-referenced theme.
            val entries = unzip(ExportCatalogZip(tenantKey = tenant.id, catalogKey = catalogKey).execute().zipBytes)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            manifest.set("resources", objectMapper.createArrayNode())
            entries.remove("resources/theme/pinned.json")
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenant.id,
                    zipBytes = rezip(entries),
                    catalogType = CatalogType.AUTHORED,
                    authoredMode = AuthoredImportMode.REPLACE,
                ).execute()
            }.isInstanceOf(CatalogUpgradeConflictException::class.java).hasMessageContaining("pinned")

            // Conflict thrown before any mutation — theme still there.
            assertThat(themeName(tenant.id, catalogKey, "pinned")).isEqualTo("Pinned")
        }
    }
}
