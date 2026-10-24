// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CheckCatalogUpgrade
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.GetReleasedResource
import app.epistola.suite.catalog.releases.ListCatalogReleases
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
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
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
import app.epistola.catalog.canonical.CatalogCanonicalizer as PortableCatalogCanonicalizer

/**
 * Versioning semantics of catalog **ZIP exchange** (contract `importCatalog`): an import installs
 * the release the archive carries, at its number, beside the tenant's releases. The same release
 * again changes nothing, and the same number with other content is refused. A next version without
 * changes of its own moves to the release it receives, as `UpgradeCatalog` does (ADR 0032); one with
 * changes stays, and the release is recorded beside it.
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
        val refreshed = LinkedHashMap(entries)
        val manifest = objectMapper.readTree(refreshed["catalog.json"]!!) as ObjectNode
        (manifest.get("release") as ObjectNode).remove("fingerprint")
        refreshed["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

        val unsigned = rawZip(refreshed)
        val read = CatalogArchiveReader.read(ByteArrayInputStream(unsigned))
        val archive = read.archive ?: return unsigned
        val fingerprint = archive.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }
        (manifest.get("release") as ObjectNode).put("fingerprint", fingerprint)
        refreshed["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        return rawZip(refreshed)
    }

    private fun rawZip(entries: Map<String, ByteArray>): ByteArray {
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
        h.createQuery("SELECT name FROM themes WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = :s")
            .bind("t", tenantKey).bindCatalog("c", testCatalog(tenantKey, catalogKey)).bind("s", slug)
            .mapTo(String::class.java).findOne().orElse(null)
    }

    @Test
    fun `an installed release exports with the fingerprint it was installed with`() {
        val src = createTenant("Zip FP Src")
        val dst = createTenant("Zip FP Dst")
        val catalogKey = CatalogKey.of("zipver-fp")

        withMediator {
            CreateCatalog(tenantKey = src.id, id = catalogKey, name = "Zip FP").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(src.id), catalogKey)), name = "Brand").execute()

            val zip1 = mediator.exportReleased(testCatalog(TenantId(src.id), catalogKey)).zipBytes
            val published = manifestOf(zip1).release

            ImportCatalogZip(tenantKey = dst.id, zipBytes = zip1).execute()
            val zip2 = ExportCatalogZip(catalog = testCatalog(dst.id, catalogKey), version = published.version).execute().zipBytes

            // Same release ⇒ the same fingerprint, end to end and tenant-independent.
            assertThat(manifestOf(zip2).release.fingerprint).isEqualTo(published.fingerprint)
        }
    }

    @Test
    fun `an import creates the catalog holding the release, its next version on it, with no source to upgrade from`() {
        val tenant = createTenant("Zip No Installed State")
        val catalogKey = CatalogKey.of("zipver-authored")

        withMediator {
            val src = createTenant("Zip Source")
            CreateCatalog(tenantKey = src.id, id = catalogKey, name = "Zip Authored").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("tee"), testCatalog(TenantId(src.id), catalogKey)), name = "T").execute()
            val zip = mediator.exportReleased(testCatalog(TenantId(src.id), catalogKey)).zipBytes

            val imported = ImportCatalogZip(tenantKey = tenant.id, zipBytes = zip).execute()

            assertThat(imported.releaseVersion to imported.newlyInstalled).isEqualTo("1.0.0" to true)
            val catalog = GetCatalog(testCatalog(tenant.id, catalogKey)).query()!!
            assertThat(catalog.installedReleaseVersion).`as`("received, not released here").isEqualTo("1.0.0")
            assertThat(catalog.releasedVersion).isNull()
            assertThat(catalog.sourceUrl).isNull()
            assertThat(ListCatalogReleases(testCatalog(tenant.id, catalogKey), CatalogReleaseStatus.DRAFT).query().map { it.isNext to it.base })
                .`as`("its next version continues on the release")
                .containsExactly(true to "1.0.0")

            assertThatThrownBy { PreviewCatalogUpgrade(testCatalog(tenant.id, catalogKey)).query() }
                .hasMessageContaining("only a catalog with a source can be upgraded")
            assertThatThrownBy { CheckCatalogUpgrade(testCatalog(tenant.id, catalogKey)).query() }
                .hasMessageContaining("only a catalog with a source can be upgraded")
        }
    }

    @Test
    fun `importing a ZIP with an older catalog schema version is rejected`() {
        val pub = createTenant("Zip Old Schema Pub")
        val consumer = createTenant("Zip Old Schema Consumer")
        val catalogKey = CatalogKey.of("zipver-oldschema")

        withMediator {
            CreateCatalog(tenantKey = pub.id, id = catalogKey, name = "Zip Old Schema").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("thm"), testCatalog(TenantId(pub.id), catalogKey)), name = "Th").execute()
            val zip = mediator.exportReleased(testCatalog(TenantId(pub.id), catalogKey)).zipBytes

            // Rewrite the manifest to an older catalog schema version (a publisher
            // on an older Epistola). A source-less ZIP can't be re-fetched and we
            // don't migrate stored content in place, so the import is rejected
            // outright — the publisher must re-export from a current source.
            val entries = unzip(zip)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            manifest.put("schemaVersion", 2)
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            assertThatThrownBy {
                ImportCatalogZip(tenantKey = consumer.id, zipBytes = rezip(entries)).execute()
            }.isInstanceOf(CatalogSchemaTooOldException::class.java)
                .hasMessageContaining("predates the oldest supported version")

            // Nothing was created.
            assertThat(GetCatalog(testCatalog(consumer.id, catalogKey)).query()).isNull()
        }
    }

    @Test
    fun `the same release again changes nothing, and the same number with other content is refused`() {
        val publisher = createTenant("Zip Rel Publisher")
        val consumer = createTenant("Zip Rel Consumer")
        val catalogKey = CatalogKey.of("zipver-adopt")

        withMediator {
            val zip = publishZip(publisher.id, "zipver-adopt", "1.0.0", mapOf("thm" to "Th"))
            // The same catalog and number, with other content: the theme renamed, fingerprinted anew.
            val entries = unzip(zip)
            val detail = objectMapper.readTree(entries["resources/theme/thm.json"]!!) as ObjectNode
            (detail.get("resource") as ObjectNode).put("name", "Another theme")
            entries["resources/theme/thm.json"] = objectMapper.writeValueAsBytes(detail)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            (manifest.get("resources") as ArrayNode).forEach { (it as ObjectNode).put("name", "Another theme") }
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
            val conflicting = rezip(entries)

            assertThat(ImportCatalogZip(consumer.id, zip).execute().newlyInstalled).isTrue()
            assertThat(ImportCatalogZip(consumer.id, zip).execute().newlyInstalled).`as`("already present; the type may be left out").isFalse()

            assertThatThrownBy { ImportCatalogZip(consumer.id, conflicting).execute() }
                .isInstanceOf(CatalogImportConflictException::class.java)
                .hasMessageContaining("one number names one release")
            assertThat(ListCatalogReleaseSummaries(testCatalog(consumer.id, catalogKey)).query().map { it.version }).containsExactly("1.0.0")
        }
    }

    @Test
    fun `a release imported into a catalog whose next version has changes of its own is added beside its releases, and its drafts are untouched`() {
        val publisher = createTenant("Zip Exist Publisher")
        val consumer = createTenant("Zip Exist Consumer")
        val catalogKey = CatalogKey.of("zipver-exist")

        withMediator {
            val v1 = publishZip(publisher.id, "zipver-exist", "1.0.0", mapOf("thm" to "Pub Th"))
            UpdateTheme(ThemeId(ThemeKey.of("thm"), testCatalog(TenantId(publisher.id), catalogKey)), name = "Pub Th 2").execute()
            val v2 = mediator.exportReleased(testCatalog(TenantId(publisher.id), catalogKey)).zipBytes

            ImportCatalogZip(tenantKey = consumer.id, zipBytes = v1).execute()
            UpdateTheme(ThemeId(ThemeKey.of("thm"), testCatalog(TenantId(consumer.id), catalogKey)), name = "Our edit").execute()

            ImportCatalogZip(tenantKey = consumer.id, zipBytes = v2).execute()

            assertThat(ListCatalogReleaseSummaries(testCatalog(consumer.id, catalogKey)).query().map { it.version }).containsExactly("1.0.1", "1.0.0")
            assertThat(themeName(consumer.id, catalogKey, "thm")).`as`("the draft is not filled").isEqualTo("Our edit")
            assertThat(GetReleasedResource(testCatalog(consumer.id, catalogKey), "1.0.1", "theme", "thm").query()?.name).isEqualTo("Pub Th 2")
        }
    }

    // ── The ZIP is the upgrade transport (UpgradeCatalog parity) ──

    /** Publisher-side helper: a catalog with the given themes, released. */
    private fun publishZip(publisherTenant: app.epistola.suite.common.ids.TenantKey, slug: String, version: String, themes: Map<String, String>): ByteArray {
        val catalogKey = CatalogKey.of(slug)
        CreateCatalog(tenantKey = publisherTenant, id = catalogKey, name = slug).execute()
        themes.forEach { (s, n) -> CreateTheme(id = ThemeId(ThemeKey.of(s), testCatalog(TenantId(publisherTenant), catalogKey)), name = n).execute() }
        MarkCatalogReady(testCatalog(publisherTenant, catalogKey)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(publisherTenant, catalogKey), version = version).execute()
        return ExportCatalogZip(catalog = testCatalog(publisherTenant, catalogKey), version = version).execute().zipBytes
    }

    @Test
    fun `importing a ZIP records installed state from the manifest`() {
        val pub = createTenant("Sub Mirror Pub")
        val sub = createTenant("Sub Mirror Sub")
        val catalogKey = CatalogKey.of("zipsub-mirror")

        withMediator {
            val zip = publishZip(pub.id, "zipsub-mirror", "1.0.0", mapOf("brand" to "Brand"))
            val manifestFp = manifestOf(zip).release.fingerprint

            ImportCatalogZip(tenantKey = sub.id, zipBytes = zip).execute()

            val catalog = GetCatalog(testCatalog(sub.id, catalogKey)).query()!!
            assertThat(catalog.sourceUrl).isNull() // ZIP-sourced, no URL
            assertThat(catalog.installedReleaseVersion).isEqualTo("1.0.0")
            assertThat(catalog.installedFingerprint).isEqualTo(manifestFp)
            // Stored naming its function, so it is never compared with values of another.
            assertThat(catalog.installedResourceFingerprints?.path("algorithm")?.asString()).isEqualTo(app.epistola.suite.catalog.BASELINE_ALGORITHM)
            assertThat(catalog.installedResourceFingerprints?.path("entries")?.has("theme/brand")).isEqualTo(true)
        }
    }

    @Test
    fun `importing the next release prunes resources the publisher dropped and advances the version`() {
        val pub = createTenant("Sub Prune Pub")
        val sub = createTenant("Sub Prune Sub")
        val catalogKey = CatalogKey.of("zipsub-prune")

        withMediator {
            val v1 = publishZip(pub.id, "zipsub-prune", "1.0.0", mapOf("keep" to "Keep", "drop" to "Drop"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = v1).execute()
            assertThat(themeName(sub.id, catalogKey, "drop")).isEqualTo("Drop")

            // Publisher's next release drops `drop`.
            val entries = unzip(v1)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            val kept = objectMapper.createArrayNode()
            (manifest.get("resources") as ArrayNode).forEach { if (it.get("key").asString() != "drop") kept.add(it) }
            manifest.set("resources", kept)
            (manifest.get("release") as ObjectNode).put("version", "2.0.0").remove("releaseId") // another release, another id
            entries.remove("resources/theme/drop.json")
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            ImportCatalogZip(tenantKey = sub.id, zipBytes = rezip(entries)).execute()

            // The next version had no changes of its own, so it moves to the release (a
            // fast-forward, UpgradeCatalog parity): stale pruned, installed version advanced.
            assertThat(themeName(sub.id, catalogKey, "drop")).isNull()
            assertThat(themeName(sub.id, catalogKey, "keep")).isEqualTo("Keep")
            assertThat(GetCatalog(testCatalog(sub.id, catalogKey)).query()!!.installedReleaseVersion).isEqualTo("2.0.0")
        }
    }

    @Test
    fun `a cross-catalog conflict blocks a ZIP upgrade and changes nothing`() {
        val pub = createTenant("Sub Conflict Pub")
        val sub = createTenant("Sub Conflict Sub")
        val catalogKey = CatalogKey.of("zipsub-conflict")

        withMediator {
            val v1 = publishZip(pub.id, "zipsub-conflict", "1.0.0", mapOf("pinned" to "Pinned"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = v1).execute()

            // A template in another catalog pins the received theme.
            val tplId = TemplateId(TestIdHelpers.nextTemplateId(), testCatalog(TenantId(sub.id), CatalogKey.DEFAULT))
            CreateDocumentTemplate(id = tplId, name = "Cross-Ref Template").execute()
            UpdateDocumentTemplate(id = tplId, themeId = ThemeKey.of("pinned"), themeCatalog = testCatalog(tplId.tenantKey, catalogKey)).execute()

            // Publisher drops the still-referenced theme in the next release.
            val entries = unzip(v1)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            manifest.set("resources", objectMapper.createArrayNode())
            (manifest.get("release") as ObjectNode).put("version", "2.0.0").remove("releaseId") // another release, another id
            entries.remove("resources/theme/pinned.json")
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            assertThatThrownBy {
                ImportCatalogZip(tenantKey = sub.id, zipBytes = rezip(entries)).execute()
            }.isInstanceOf(CatalogUpgradeConflictException::class.java).hasMessageContaining("pinned")

            // Conflict thrown before any mutation: version + theme unchanged.
            assertThat(GetCatalog(testCatalog(sub.id, catalogKey)).query()!!.installedReleaseVersion).isEqualTo("1.0.0")
            assertThat(themeName(sub.id, catalogKey, "pinned")).isEqualTo("Pinned")
        }
    }

    @Test
    fun `a failed resource aborts a ZIP upgrade — installed version unchanged, stale not pruned`() {
        val pub = createTenant("Sub Abort Pub")
        val sub = createTenant("Sub Abort Sub")
        val catalogKey = CatalogKey.of("zipsub-abort")

        withMediator {
            val v1 = publishZip(pub.id, "zipsub-abort", "1.0.0", mapOf("keep" to "Keep", "drop" to "Drop"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = v1).execute()

            // Next "release": drop `drop` (would be pruned on success) and bump
            // to 2.0.0 — but corrupt `keep` by removing its detail file so its
            // re-install FAILS.
            val entries = unzip(v1)
            val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
            val kept = objectMapper.createArrayNode()
            (manifest.get("resources") as ArrayNode).forEach { if (it.get("key").asString() != "drop") kept.add(it) }
            manifest.set("resources", kept)
            (manifest.get("release") as ObjectNode).put("version", "2.0.0").remove("releaseId") // another release, another id
            entries.remove("resources/theme/drop.json")
            entries.remove("resources/theme/keep.json") // referenced by manifest → install FAILS
            entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = sub.id,
                    zipBytes = rezip(entries),
                ).execute()
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("CATALOG_MANIFEST_DETAIL_MISSING")

            // Whole-catalog validation fails before mutation: version is not
            // advanced and stale `drop` is not pruned.
            assertThat(GetCatalog(testCatalog(sub.id, catalogKey)).query()!!.installedReleaseVersion).isEqualTo("1.0.0")
            assertThat(themeName(sub.id, catalogKey, "drop")).isEqualTo("Drop")
        }
    }

    @Test
    fun `browsing a catalog imported from a ZIP lists installed resources instead of erroring (no source URL)`() {
        val pub = createTenant("Sub Browse Pub")
        val sub = createTenant("Sub Browse Sub")
        val catalogKey = CatalogKey.of("zipsub-browse")

        withMediator {
            val zip = publishZip(pub.id, "zipsub-browse", "1.0.0", mapOf("brand" to "Brand", "alt" to "Alt"))
            ImportCatalogZip(tenantKey = sub.id, zipBytes = zip).execute()

            // Regression: a catalog imported from a ZIP has no source URL — browsing
            // must NOT throw "Subscribed catalog has no source URL"; it lists
            // the locally-installed resources.
            val result = BrowseCatalog(testCatalog(sub.id, catalogKey)).query()

            assertThat(result.catalog.hasSource).isFalse()
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
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(pub.id), catalogKey)), name = "Brand").execute()
            CreateCodeList(
                id = CodeListId(CodeListKey.of("bcp-47"), testCatalog(TenantId(pub.id), catalogKey)),
                displayName = "BCP-47 Language Tags",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English"), CodeListEntry("nl", "Dutch")),
            ).execute()
            MarkCatalogReady(testCatalog(pub.id, catalogKey)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(pub.id, catalogKey), version = "1.0.0").execute()
            val zip = ExportCatalogZip(catalog = testCatalog(pub.id, catalogKey), version = "1.0.0").execute().zipBytes

            ImportCatalogZip(tenantKey = sub.id, zipBytes = zip).execute()

            // Regression: BrowseCatalog's installed-detection union omitted
            // code_lists (and fonts), so an installed code list showed as
            // "Available" (the BCP-47-in-system bug).
            val codeList = BrowseCatalog(testCatalog(sub.id, catalogKey)).query().resources
                .single { it.type == "codeList" && it.slug == "bcp-47" }
            assertThat(codeList.status.name).isEqualTo("INSTALLED")
            assertThat(codeList.name).isEqualTo("BCP-47 Language Tags")
        }
    }
}
