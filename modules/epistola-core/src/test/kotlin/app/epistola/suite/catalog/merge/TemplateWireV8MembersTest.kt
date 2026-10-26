// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseTargeting
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
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
 * What wire v8 added to a template -- its `description`, its `metadata`, a data example's
 * `key` -- is stored, so importing, copying a release into a draft, merging and building content
 * keep it, and a resource that differs from a release in nothing else compares equal to it.
 */
class TemplateWireV8MembersTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var fingerprints: CatalogFingerprintService

    @Autowired private lateinit var contentBuilder: CatalogContentBuilder

    @Autowired private lateinit var targeting: CatalogReleaseTargeting

    private val catalog = CatalogKey.of("wire-members")

    private val metadata = mapOf("com.example.layout" to mapOf("grid" to "a4-two-column", "columns" to 2))

    private fun theme(tenant: TenantKey, key: String) = ThemeId(ThemeKey.of(key), testCatalog(TenantId(tenant), catalog))

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
     * [archive] with template `letter` changed by [edit] and released as [version] (made from [base]
     * when given), fingerprinted again: a release from a producer that writes these members.
     */
    private fun released(archive: ByteArray, version: String, base: Pair<String, String>?, edit: (ObjectNode) -> Unit): ByteArray {
        val all = entries(archive)
        val manifest = objectMapper.readTree(all["catalog.json"]!!) as ObjectNode
        val entry = manifest.get("resources").first { it.get("type").asString() == "template" && it.get("key").asString() == "letter" } as ObjectNode
        val detailPath = entry.get("detailUrl").asString().removePrefix("./")
        val detail = objectMapper.readTree(all[detailPath]!!) as ObjectNode
        val template = detail.get("resource") as ObjectNode
        edit(template)
        // The manifest entry's description is the template's own (wire v8).
        template.get("description")?.let { entry.set("description", it) } ?: entry.remove("description")
        all[detailPath] = objectMapper.writeValueAsBytes(detail)
        val release = manifest.get("release") as ObjectNode
        release.put("version", version)
        release.remove(listOf("releaseId", "fingerprint"))
        if (base != null) release.putObject("base").put("version", base.first).put("fingerprint", base.second) else release.remove("base")
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        val fingerprint = CatalogArchiveReader.read(ByteArrayInputStream(zip(all))).archive!!.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }
        release.put("fingerprint", fingerprint)
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        return zip(all)
    }

    private fun ObjectNode.withMetadata(value: Map<String, Map<String, Any>>) = apply {
        set("metadata", objectMapper.valueToTree(value))
    }

    private fun letter(content: CatalogContent): TemplateResource = content.resourceDetails.getValue("template/letter").resource as TemplateResource

    private fun archiveFingerprint(zip: ByteArray, key: String): String = CatalogArchiveReader.read(ByteArrayInputStream(zip)).archive!!.use { fingerprints.resourceFingerprints(it).getValue(key) }

    /** Publisher [a]'s release 1.0.0: a described template with a data example, and a theme. */
    private fun publish(a: TenantKey): ByteArray = withMediator {
        CreateCatalog(tenantKey = a, id = catalog, name = "Wire Members").execute()
        CreateTheme(id = theme(a, "brand"), name = "Brand").execute()
        CreateDocumentTemplate(TemplateId(TemplateKey.of("letter"), testCatalog(TenantId(a), catalog)), "Letter", description = "For letters to residents").execute()
            .withRequiredDataExample()
        MarkCatalogReady(testCatalog(a, catalog)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()
        ExportCatalogZip(testCatalog(a, catalog), "1.0.0").execute().zipBytes
    }

    @Test
    fun `an imported template keeps its description, its metadata and its examples' keys`() {
        val a = createTenant("Wire Members A").id
        val b = createTenant("Wire Members B").id
        val v100 = released(publish(a), "1.0.0", null) { it.withMetadata(metadata) }

        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = v100).execute()
            val built = contentBuilder.build(testCatalog(b, catalog))
            val template = letter(built)

            assertThat(template.description).isEqualTo("For letters to residents")
            assertThat(built.resourceEntries.single { it.key == "letter" }.description).isEqualTo("For letters to residents")
            assertThat(template.metadata).isEqualTo(metadata)
            assertThat(template.dataExamples.orEmpty().map { it.key }).containsExactly("example-1")
            // Built back from the rows, the template is the one the release holds.
            assertThat(fingerprints.resourceFingerprints(built).getValue("template/letter")).isEqualTo(archiveFingerprint(v100, "template/letter"))
        }
    }

    @Test
    fun `a template's metadata counts in no fingerprint`() {
        val a = createTenant("Wire Metadata Hash A").id
        val base = publish(a)
        val plain = released(base, "1.0.0", null) { }
        val described = released(base, "1.0.0", null) { it.withMetadata(metadata) }

        assertThat(archiveFingerprint(described, "template/letter")).isEqualTo(archiveFingerprint(plain, "template/letter"))
        val catalogFingerprint = { zip: ByteArray -> CatalogArchiveReader.read(ByteArrayInputStream(zip)).archive!!.use { it.manifest.release.fingerprint } }
        assertThat(catalogFingerprint(described)).isEqualTo(catalogFingerprint(plain))
    }

    @Test
    fun `an imported variant keeps its description, and the template built back from the rows is the release's`() {
        val a = createTenant("Wire Variant Description A").id
        val b = createTenant("Wire Variant Description B").id
        val v100 = released(publish(a), "1.0.0", null) { template -> template.get("variants").forEach { (it as ObjectNode).put("description", "For courts") } }

        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = v100).execute()
            val built = contentBuilder.build(testCatalog(b, catalog))

            assertThat(letter(built).variants.single().description).isEqualTo("For courts")
            assertThat(fingerprints.resourceFingerprints(built).getValue("template/letter")).isEqualTo(archiveFingerprint(v100, "template/letter"))
        }
    }

    @Test
    fun `a draft started from a release keeps them`() {
        val a = createTenant("Wire Draft A").id
        val b = createTenant("Wire Draft B").id
        val v100 = released(publish(a), "1.0.0", null) { it.withMetadata(metadata) }

        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = v100).execute()
            val draft = CreateCatalogDraft(testCatalog(b, catalog), name = "Redesign", from = "1.0.0").execute()
            val id = targeting.resolve(CatalogReleaseRef(testCatalog(b, catalog), DraftRef.Id(draft.versionId)))
            val built = targeting.within(id) { contentBuilder.build(testCatalog(b, catalog)) }

            assertThat(letter(built).description).isEqualTo("For letters to residents")
            assertThat(letter(built).metadata).isEqualTo(metadata)
            assertThat(fingerprints.resourceFingerprints(built).getValue("template/letter")).isEqualTo(archiveFingerprint(v100, "template/letter"))
        }
    }

    @Test
    fun `a release that changes a template's metadata and description is taken by a merge, and the draft keeps it`() {
        val a = createTenant("Wire Merge A").id
        val b = createTenant("Wire Merge B").id
        val v100 = publish(a)
        val fingerprint = withMediator { ListCatalogReleaseSummaries(testCatalog(a, catalog)).query().single().fingerprint }

        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = v100).execute()
            UpdateTheme(theme(b, "brand"), name = "B's brand").execute()
            ImportCatalogZip(tenantKey = b, zipBytes = released(v100, "1.0.1", "1.0.0" to fingerprint) { it.withMetadata(metadata).put("description", "Described again") }).execute()

            val preview = PreviewDraftMerge(testCatalog(b, catalog), "1.0.1").query()
            assertThat(preview.resources.single { it.contentKey == "template/letter" }.outcome).isEqualTo(MergeOutcome.THEIRS)
            assertThat(MergeReleaseIntoDraft(testCatalog(b, catalog), "1.0.1").execute().taken).containsExactly("template/letter")

            val template = letter(contentBuilder.build(testCatalog(b, catalog)))
            assertThat(template.metadata).isEqualTo(metadata)
            assertThat(template.description).isEqualTo("Described again")
        }
    }

    @Test
    fun `a template that differs from the release in nothing else is no conflict`() {
        val a = createTenant("Wire Conflict A").id
        val b = createTenant("Wire Conflict B").id
        val v100 = released(publish(a), "1.0.0", null) { it.withMetadata(metadata) }
        val fingerprint = CatalogArchiveReader.read(ByteArrayInputStream(v100)).archive!!.use { it.manifest.release.fingerprint!! }

        withMediator { ImportCatalogZip(tenantKey = b, zipBytes = v100).execute() }
        // A next version whose baseline is as good as none (one recorded by another function), so it
        // is compared with the release's content itself: a template that lost a member there is a
        // change of its own. No command produces that state, so it is put in place directly.
        jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE catalog_versions SET base_fingerprints = NULL WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND is_next")
                .bind("t", b).bindCatalog("c", testCatalog(b, catalog)).execute()
        }
        withMediator {
            UpdateTheme(theme(b, "brand"), name = "B's brand").execute()
            ImportCatalogZip(tenantKey = b, zipBytes = released(v100, "1.0.1", "1.0.0" to fingerprint) { it.put("description", "Described again") }).execute()

            val preview = PreviewDraftMerge(testCatalog(b, catalog), "1.0.1").query()
            assertThat(preview.resources.single { it.contentKey == "template/letter" }.outcome).isEqualTo(MergeOutcome.THEIRS)
        }
    }
}
