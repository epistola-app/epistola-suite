// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.suite.assets.AssetIntegrationTest
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
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
 * A received resource's producer metadata (contract `CatalogResource.metadata`) is kept on every type
 * stored as JSON: installing a release stores it, an author's edit keeps it, building the catalog's
 * content and exporting it carry it, and it counts in no fingerprint.
 */
class ResourceMetadataTest : IntegrationTestBase() {

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var contentBuilder: CatalogContentBuilder

    @Autowired private lateinit var fingerprints: CatalogFingerprintService

    private val catalog = CatalogKey.of("described")

    private val metadata = mapOf("com.example.review" to mapOf("reviewedBy" to "design", "round" to 2))

    /** Publisher [a]'s release 1.0.0: a theme, a stencil, a code list, an attribute and an image. */
    private fun publish(a: TenantKey): ByteArray = withMediator {
        val id = testCatalog(a, catalog)
        CreateCatalog(tenantKey = a, id = catalog, name = "Described").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), id), name = "Brand").execute()
        CreateStencil(StencilId(StencilKey.of("header"), id), "Header").execute()
        CreateCodeList(CodeListId(CodeListKey.of("languages"), id), "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands"))).execute()
        CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), id), "Tone", allowedValues = listOf("formal", "plain")).execute()
        UploadAsset(id, "logo", AssetMediaType.PNG, AssetIntegrationTest.createMinimalPng(), 1, 1).execute()
        MarkCatalogReady(id).execute()
        ReleaseCatalogDraft(catalog = id, version = "1.0.0").execute()
        ExportCatalogZip(id, "1.0.0").execute().zipBytes
    }

    /** [archive] with [metadata] on every resource but templates, fingerprinted again. */
    private fun stamped(archive: ByteArray): ByteArray {
        val all = entries(archive)
        val manifest = objectMapper.readTree(all["catalog.json"]!!) as ObjectNode
        manifest.get("resources").forEach { entry ->
            val path = entry.get("detailUrl").asString().removePrefix("./")
            val detail = objectMapper.readTree(all[path]!!) as ObjectNode
            (detail.get("resource") as ObjectNode).set("metadata", objectMapper.valueToTree(metadata))
            all[path] = objectMapper.writeValueAsBytes(detail)
        }
        val release = manifest.get("release") as ObjectNode
        release.remove(listOf("releaseId", "fingerprint"))
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        val fingerprint = CatalogArchiveReader.read(ByteArrayInputStream(zip(all))).archive!!.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }
        release.put("fingerprint", fingerprint)
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        return zip(all)
    }

    @Test
    fun `every type keeps a received resource's metadata through install, edit and export, outside the fingerprint`() {
        val a = createTenant("Metadata A").id
        val b = createTenant("Metadata B").id
        val plain = publish(a)
        val described = stamped(plain)

        val releaseFingerprint = { zip: ByteArray -> CatalogArchiveReader.read(ByteArrayInputStream(zip)).archive!!.use { it.manifest.release.fingerprint } }
        assertThat(releaseFingerprint(described)).`as`("metadata counts in no fingerprint").isEqualTo(releaseFingerprint(plain))

        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = described).execute()
            val installed = contentBuilder.build(testCatalog(b, catalog))
            val keys = installed.resourceDetails.keys.toList()
            assertThat(keys.map { it.substringBefore('/') }).`as`("one of each type").containsExactlyInAnyOrder("theme", "stencil", "codeList", "attribute", "image")
            keys.forEach { key ->
                assertThat(installed.resourceDetails.getValue(key).resource.metadata).`as`("installed $key").isEqualTo(metadata)
            }
            assertThat(fingerprints.resourceFingerprints(installed)).`as`("built back, every resource is the release's")
                .containsAllEntriesOf(CatalogArchiveReader.read(ByteArrayInputStream(described)).archive!!.use { fingerprints.resourceFingerprints(it) })

            // An author's edits keep what the producer said.
            UpdateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(b, catalog)), name = "B's brand").execute()
            UpdateStencil(StencilId(StencilKey.of("header"), testCatalog(b, catalog)), name = "B's header").execute()
            val edited = contentBuilder.build(testCatalog(b, catalog))
            assertThat(edited.resourceDetails.getValue("theme/brand").resource.metadata).`as`("an edited theme").isEqualTo(metadata)
            assertThat(edited.resourceDetails.getValue("stencil/header").resource.metadata).`as`("an edited stencil").isEqualTo(metadata)
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
