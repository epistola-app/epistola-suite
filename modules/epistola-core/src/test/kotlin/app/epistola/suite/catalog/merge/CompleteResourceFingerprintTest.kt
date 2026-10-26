// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.isNext
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.NextVersionChanges
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import app.epistola.catalog.canonical.CatalogCanonicalizer as PortableCatalogCanonicalizer

/**
 * Resources are told apart by their complete per-resource fingerprint (contract
 * `completePerResourceFingerprints`), which covers what wire v8 added to a resource; and a baseline
 * stored by another function is never compared with it.
 */
class CompleteResourceFingerprintTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var fingerprints: CatalogFingerprintService

    @Autowired private lateinit var nextVersionChanges: NextVersionChanges

    @Autowired private lateinit var contentBuilder: app.epistola.suite.catalog.CatalogContentBuilder

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), testCatalog(TenantId(tenant), catalog))

    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
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

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /**
     * [archive] (release 1.0.0) as release 1.0.1 made from it whose only change is the description of
     * template [template], fingerprinted again: a release from a producer that keeps descriptions.
     */
    private fun describedRelease(archive: ByteArray, template: String, base: String): ByteArray {
        val all = entries(archive)
        val manifest = objectMapper.readTree(all["catalog.json"]!!) as ObjectNode
        val detailPath = manifest.get("resources").first { it.get("type").asString() == "template" && it.get("key").asString() == template }
            .get("detailUrl").asString().removePrefix("./")
        val detail = objectMapper.readTree(all[detailPath]!!) as ObjectNode
        (detail.get("resource") as ObjectNode).put("description", "Now described")
        all[detailPath] = objectMapper.writeValueAsBytes(detail)
        val release = manifest.get("release") as ObjectNode
        release.put("version", "1.0.1")
        release.remove(listOf("releaseId", "fingerprint"))
        release.putObject("base").put("version", "1.0.0").put("fingerprint", base)
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        val fingerprint = CatalogArchiveReader.read(ByteArrayInputStream(zip(all))).archive!!.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }
        release.put("fingerprint", fingerprint)
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        return zip(all)
    }

    @Test
    fun `a release whose only change is a template's description is taken by a merge`() {
        val a = createTenant("Complete FP A").id
        val b = createTenant("Complete FP B").id
        val catalog = CatalogKey.of("complete-fp")
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "Complete FP").execute()
            CreateTheme(id = theme(a, catalog, "brand"), name = "Brand").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("letter"), testCatalog(TenantId(a), catalog)), "Letter").execute().withRequiredDataExample()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()
            val v100 = ExportCatalogZip(testCatalog(a, catalog), "1.0.0").execute().zipBytes
            val fingerprint = ListCatalogReleaseSummaries(testCatalog(a, catalog)).query().single().fingerprint

            ImportCatalogZip(tenantKey = b, zipBytes = v100).execute()
            UpdateTheme(theme(b, catalog, "brand"), name = "B's brand").execute()
            ImportCatalogZip(tenantKey = b, zipBytes = describedRelease(v100, "letter", fingerprint)).execute()

            val preview = PreviewDraftMerge(testCatalog(b, catalog), "1.0.1").query()
            assertThat(preview.base).isEqualTo("1.0.0")
            assertThat(preview.resources.single { it.contentKey == "template/letter" }.outcome).isEqualTo(MergeOutcome.THEIRS)
            assertThat(MergeReleaseIntoDraft(testCatalog(b, catalog), "1.0.1").execute().taken).containsExactly("template/letter")
        }
    }

    @Test
    fun `a baseline another function stored is as good as none`() {
        val a = createTenant("Legacy Baseline A").id
        val b = createTenant("Legacy Baseline B").id
        val catalog = CatalogKey.of("legacy-baseline")
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "Legacy Baseline").execute()
            CreateTheme(id = theme(a, catalog, "brand"), name = "Brand").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()
            ImportCatalogZip(tenantKey = b, zipBytes = ExportCatalogZip(testCatalog(a, catalog), "1.0.0").execute().zipBytes).execute()
        }
        // What a next version recorded before baselines named their function: V3 values, a plain map.
        // No command writes that form any more, so it is put in place directly.
        val legacy = withMediator { objectMapper.writeValueAsString(fingerprints.perResourceFingerprints(contentBuilder.build(testCatalog(b, catalog)))) }
        jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE catalog_versions SET base_fingerprints = CAST(:f AS JSONB) WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND ${isNext("catalog_versions")}")
                .bind("f", legacy).bind("t", b).bindCatalog("c", testCatalog(b, catalog)).execute()
        }
        withMediator {
            assertThat(nextVersionChanges.changed(testCatalog(b, catalog))).isEmpty()
            assertThat(GetCatalogResourceChanges(testCatalog(b, catalog)).query().resources.map { it.state }.distinct()).containsExactly(CatalogResourceState.RELEASED)
        }
    }
}
