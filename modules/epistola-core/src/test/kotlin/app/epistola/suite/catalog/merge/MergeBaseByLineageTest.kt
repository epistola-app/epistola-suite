// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.parallelReleaseLines
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

/**
 * The merge base is found by the lineage releases record, never guessed from their numbers (contract
 * ADR 0005): when this installation lacks it the merge says which release to get, and when no
 * release both lines share is known every difference is a choice.
 */
class MergeBaseByLineageTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), CatalogId(catalog, TenantId(tenant)))

    /** The names of the next release's themes, by key. */
    private fun themes(tenant: TenantKey, catalog: CatalogKey): Map<String, String> = jdbi.withHandle<Map<String, String>, Exception> { h ->
        h.createQuery(
            "SELECT t.id, t.name FROM themes t JOIN catalog_current_versions v ON v.tenant_key = t.tenant_key AND v.version_id = t.version_id " +
                "WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")}",
        )
            .bind("t", tenant).bind("c", catalog)
            .map { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list().toMap()
    }

    @Test
    fun `a release whose merge base is not here says which to get, and merges exactly once it is`() {
        val a = createTenant("Lineage A").id
        val b = createTenant("Lineage B").id
        val catalog = CatalogKey.of("lineage")
        withMediator {
            val lines = mediator.parallelReleaseLines(a, catalog)
            val v100 = ListCatalogReleaseSummaries(a, catalog).query().single { it.version == "1.0.0" }

            // B received 1.1.0 only, then the fix 1.0.1, made from 1.0.0 on the other line.
            ImportCatalogZip(tenantKey = b, zipBytes = lines.v110).execute()
            ImportCatalogZip(tenantKey = b, zipBytes = lines.v101).execute()

            val missing = PreviewDraftMerge(b, catalog, "1.0.1").query()
            assertThat(missing.missingBase).isEqualTo(MergeBaseRef("1.0.0", v100.fingerprint))
            assertThat(missing.base).isNull()
            assertThat(missing.resources).isEmpty()
            assertThat(missing.noCommonRelease).isFalse()
            assertThatThrownBy { MergeReleaseIntoDraft(b, catalog, "1.0.1").execute() }
                .isInstanceOfSatisfying(MergeBaseMissingException::class.java) {
                    assertThat(it.version).isEqualTo("1.0.0")
                    assertThat(it.fingerprint).isEqualTo(v100.fingerprint)
                }
                .hasMessage("To merge v1.0.1, this installation needs v1.0.0, the release both come from. Get it first (from a registry, the Exchange or a file).")

            // Once B has 1.0.0, beside the others, the merge compares against it.
            ImportCatalogZip(tenantKey = b, zipBytes = lines.v100).execute()
            val preview = PreviewDraftMerge(b, catalog, "1.0.1").query()
            assertThat(preview.missingBase).isNull()
            assertThat(preview.base).isEqualTo("1.0.0")
            assertThat(preview.resources.filter { it.outcome != MergeOutcome.UNCHANGED }.associate { it.contentKey to it.outcome })
                .containsExactlyInAnyOrderEntriesOf(mapOf("theme/brand" to MergeOutcome.THEIRS, "theme/added" to MergeOutcome.OURS))

            val merged = MergeReleaseIntoDraft(b, catalog, "1.0.1", expectedToken = preview.token).execute()
            assertThat(merged.taken).containsExactly("theme/brand")
        }
        // 1.0.1's change taken, 1.1.0's addition kept.
        assertThat(themes(b, catalog)).containsExactlyInAnyOrderEntriesOf(mapOf("brand" to "Brand, fixed", "letterhead" to "Letterhead", "added" to "Added"))
    }

    @Test
    fun `a release that names no base shares no release with the draft, and every difference is a choice`() {
        val a = createTenant("No Lineage A").id
        val b = createTenant("No Lineage B").id
        val catalog = CatalogKey.of("no-lineage")
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "No Lineage").execute()
            listOf("thx", "thy", "thz").forEach { CreateTheme(id = theme(a, catalog, it), name = "base $it").execute() }
            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogDraft(tenantKey = a, catalogKey = catalog, version = "1.0.0").execute()
            UpdateTheme(theme(a, catalog, "thx"), name = "A's x").execute()
            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogDraft(tenantKey = a, catalogKey = catalog, version = "1.1.0").execute()
            // An archive from before releases named their base.
            val v110 = withoutBase(ExportCatalogZip(a, catalog, "1.1.0").execute().zipBytes)

            ImportCatalogZip(tenantKey = b, zipBytes = ExportCatalogZip(a, catalog, "1.0.0").execute().zipBytes).execute()
            UpdateTheme(theme(b, catalog, "thy"), name = "B's y").execute()
            ImportCatalogZip(tenantKey = b, zipBytes = v110).execute()

            val preview = PreviewDraftMerge(b, catalog, "1.1.0").query()
            assertThat(preview.base).isNull()
            assertThat(preview.missingBase).isNull()
            assertThat(preview.noCommonRelease).isTrue()
            // Not 1.0.0 because it is numbered below: x and y both differ, and neither is guessed.
            assertThat(preview.conflicts.map { it.contentKey }).containsExactlyInAnyOrder("theme/thx", "theme/thy")
            assertThat(preview.resources.single { it.contentKey == "theme/thz" }.outcome).isEqualTo(MergeOutcome.SAME)
        }
    }

    private fun withoutBase(bytes: ByteArray): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zip.readAllBytes()
                e = zip.nextEntry
            }
        }
        val manifest = objectMapper.readTree(entries["catalog.json"]!!) as ObjectNode
        (manifest.get("release") as ObjectNode).remove("base")
        entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
