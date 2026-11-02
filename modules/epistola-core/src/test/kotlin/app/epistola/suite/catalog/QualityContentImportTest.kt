// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * Contract 2.0.0-alpha.6 adds quality profiles to the catalog wire format (v8, contract ADR 0010):
 * a `qualityProfile` resource, and a `quality` binding on the catalog and on a template. The suite
 * stores none of them yet (ADR 0046), so a release carrying one is refused, saying what it carries,
 * rather than received without it, which would keep something other than what the release is.
 */
class QualityContentImportTest : IntegrationTestBase() {

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val catalog = CatalogKey.of("checked")

    private val binding = mapOf("profiles" to listOf(mapOf("key" to "house-style")))

    /** Publisher [a]'s release 1.0.0 of [catalog]: one template. */
    private fun publish(a: TenantKey): ByteArray = withMediator {
        val id = testCatalog(a, catalog)
        CreateCatalog(tenantKey = a, id = catalog, name = "Checked").execute()
        CreateDocumentTemplate(id = TemplateId(TemplateKey.of("letter"), id), name = "Letter").execute()
        MarkCatalogReady(id).execute()
        ReleaseCatalogDraft(catalog = id, version = "1.0.0").execute()
        ExportCatalogZip(id, "1.0.0").execute().zipBytes
    }

    @Test
    fun `a release carrying a quality profile is refused`() {
        assertRefused("quality profile 'house-style'") { _, _ -> }
    }

    @Test
    fun `a release binding quality profiles on its catalog is refused`() {
        assertRefused("a quality binding on the catalog") { manifest, _ ->
            (manifest.get("catalog") as ObjectNode).set("quality", objectMapper.valueToTree(binding))
        }
    }

    @Test
    fun `a release binding quality profiles on a template is refused`() {
        assertRefused("a quality binding on template 'letter'") { manifest, all ->
            val path = manifest.get("resources").first { it.get("type").asString() == "template" }.get("detailUrl").asString().removePrefix("./")
            val detail = objectMapper.readTree(all[path]!!) as ObjectNode
            (detail.get("resource") as ObjectNode).set("quality", objectMapper.valueToTree(binding))
            all[path] = objectMapper.writeValueAsBytes(detail)
        }
    }

    /** Adds the quality profile `house-style` (contract `quality-profiles.md`) to the archive [all] described by [manifest]. */
    private fun addProfile(manifest: ObjectNode, all: LinkedHashMap<String, ByteArray>) {
        val profile = mapOf(
            "schemaVersion" to manifest.get("schemaVersion").asInt(),
            "resource" to mapOf(
                "type" to "qualityProfile",
                "key" to "house-style",
                "name" to "House style",
                "rules" to listOf(mapOf("id" to "short-sentences", "title" to "Short sentences", "intent" to "Keep sentences short.", "severity" to "warning")),
            ),
        )
        all["resources/qualityProfile/house-style.json"] = objectMapper.writeValueAsBytes(profile)
        (manifest.get("resources") as ArrayNode).add(
            objectMapper.valueToTree<ObjectNode>(mapOf("type" to "qualityProfile", "key" to "house-style", "name" to "House style", "detailUrl" to "./resources/qualityProfile/house-style.json")),
        )
    }

    /** Publishes, changes the archive with [change], fingerprints it again, and imports it into another tenant. */
    private fun assertRefused(carries: String, change: (ObjectNode, LinkedHashMap<String, ByteArray>) -> Unit) {
        val a = createTenant("Quality A").id
        val b = createTenant("Quality B").id
        val all = entries(publish(a))
        val manifest = objectMapper.readTree(all["catalog.json"]!!) as ObjectNode
        addProfile(manifest, all)
        change(manifest, all)
        val release = manifest.get("release") as ObjectNode
        release.remove(listOf("releaseId", "fingerprint"))
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        val fingerprint = CatalogArchiveReader.read(ByteArrayInputStream(zip(all))).archive!!.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }
        release.put("fingerprint", fingerprint)
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

        withMediator {
            assertThatThrownBy { ImportCatalogZip(tenantKey = b, zipBytes = zip(all), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(carries)
                .hasMessageContaining("does not support quality profiles yet")
            assertThat(GetCatalog(testCatalog(b, catalog)).query()).`as`("nothing of the release is kept").isNull()
        }
    }

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
}
