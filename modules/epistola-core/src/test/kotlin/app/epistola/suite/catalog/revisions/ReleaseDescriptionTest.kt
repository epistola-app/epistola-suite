// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ReleaseBase
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CatalogImportConflictException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleaseRenderingDefaultsTooNewException
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
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
import app.epistola.catalog.canonical.CatalogCanonicalizer as PortableCatalogCanonicalizer

/**
 * A release says what it is wherever it goes (catalog wire v8): its id, the rendering defaults it
 * renders with, and the release it was made from, in its snapshot and in every archive of it, none of
 * it moving its fingerprint.
 */
class ReleaseDescriptionTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var installation: app.epistola.suite.installation.InstallationService

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), testCatalog(TenantId(tenant), catalog))

    internal fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
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

    internal fun zip(entries: Map<String, ByteArray>): ByteArray {
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

    private fun manifestOf(bytes: ByteArray): CatalogManifest = objectMapper.readValue(entries(bytes)["catalog.json"]!!, CatalogManifest::class.java)

    /** [bytes] with its release changed by [edit] and nothing else. */
    private fun withRelease(bytes: ByteArray, edit: (ObjectNode) -> Unit): ByteArray {
        val all = entries(bytes)
        val manifest = objectMapper.readTree(all["catalog.json"]!!) as ObjectNode
        edit(manifest.get("release") as ObjectNode)
        all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        return zip(all)
    }

    private fun snapshotRelease(tenant: TenantKey, catalog: CatalogKey, version: String): CatalogManifest = jdbi.withHandle<CatalogManifest, Exception> { h ->
        h.createQuery("SELECT manifest_snapshot::text FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
            .bind("t", tenant).bindCatalog("c", testCatalog(tenant, catalog)).bind("v", version)
            .map { rs, _ -> objectMapper.readValue(rs.getString(1), CatalogManifest::class.java) }
            .one()
    }

    @Test
    fun `a release names its id, its rendering defaults and the release it was made from, in its snapshot and its archive`() {
        val a = createTenant("Release Description").id
        val catalog = CatalogKey.of("release-description")
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "Release Description").execute()
            CreateTheme(id = theme(a, catalog, "brand"), name = "Brand").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()
            UpdateTheme(theme(a, catalog, "brand"), name = "Brand 2").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.1.0").execute()

            val releases = ListCatalogReleases(testCatalog(a, catalog)).query().filter { it.status == CatalogReleaseStatus.RELEASED }.associateBy { it.version }
            val first = manifestOf(ExportCatalogZip(testCatalog(a, catalog), "1.0.0").execute().zipBytes).release
            val second = manifestOf(ExportCatalogZip(testCatalog(a, catalog), "1.1.0").execute().zipBytes).release
            val metadata = mapOf("app.epistola.suite" to mapOf("renderingDefaultsVersion" to RenderingDefaults.CURRENT.version, "releasedByInstallation" to installation.get().id.toString()))

            assertThat(first.releaseId).isEqualTo(releases.getValue("1.0.0").versionId)
            assertThat(first.metadata).isEqualTo(metadata)
            assertThat(first.base).isNull()
            assertThat(second.releaseId).isEqualTo(releases.getValue("1.1.0").versionId)
            assertThat(second.metadata).isEqualTo(metadata)
            assertThat(second.base).isEqualTo(ReleaseBase("1.0.0", releases.getValue("1.0.0").fingerprint!!))

            // The snapshot says the same as the archive.
            assertThat(snapshotRelease(a, catalog, "1.1.0").release).isEqualTo(second)
        }
    }

    @Test
    fun `what a release says of itself does not move its fingerprint`() {
        val a = createTenant("Release Description FP").id
        val catalog = CatalogKey.of("release-description-fp")
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "Release Description FP").execute()
            CreateTheme(id = theme(a, catalog, "brand"), name = "Brand").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()
            UpdateTheme(theme(a, catalog, "brand"), name = "Brand 2").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            val released = ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.1.0").execute()
            val archive = ExportCatalogZip(testCatalog(a, catalog), "1.1.0").execute().zipBytes
            val bare = withRelease(archive) { it.remove(listOf("releaseId", "metadata", "base")) }

            fun fingerprintOf(bytes: ByteArray) = CatalogArchiveReader.read(ByteArrayInputStream(bytes)).archive!!.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }
            assertThat(manifestOf(archive).release.base).isNotNull()
            assertThat(fingerprintOf(archive)).isEqualTo(released.fingerprint).isEqualTo(fingerprintOf(bare))
            assertThat(manifestOf(archive).release.fingerprint).isEqualTo(released.fingerprint)
        }
    }

    private data class Lineage(val a: TenantKey, val catalog: CatalogKey, val v100: ByteArray, val v110: ByteArray)

    /** A releases 1.0.0 and then 1.1.0 from it; both archives. */
    private fun lineage(name: String): Lineage {
        val a = createTenant("$name A").id
        val catalog = CatalogKey.of(name.lowercase().replace(' ', '-'))
        return withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = name).execute()
            CreateTheme(id = theme(a, catalog, "brand"), name = "Brand").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()
            UpdateTheme(theme(a, catalog, "brand"), name = "Brand 2").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.1.0").execute()
            Lineage(a, catalog, ExportCatalogZip(testCatalog(a, catalog), "1.0.0").execute().zipBytes, ExportCatalogZip(testCatalog(a, catalog), "1.1.0").execute().zipBytes)
        }
    }

    private data class Recorded(val versionKey: String, val renderingDefaults: Int?, val baseVersion: String?, val baseFingerprint: String?)

    private fun recorded(tenant: TenantKey, catalog: CatalogKey, version: String): Recorded? = jdbi.withHandle<Recorded?, Exception> { h ->
        h.createQuery("SELECT version_key, rendering_defaults_version, base_version, base_fingerprint FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
            .bind("t", tenant).bindCatalog("c", testCatalog(tenant, catalog)).bind("v", version)
            .map { rs, _ -> Recorded(rs.getString(1), rs.getObject(2) as Int?, rs.getString(3), rs.getString(4)) }
            .findOne().orElse(null)
    }

    @Test
    fun `a received release keeps its id, its metadata and the release it was made from, and exports saying so`() {
        val (a, catalog, v100, v110) = lineage("Received Description")
        val b = createTenant("Received Description B").id
        val sent = manifestOf(v110).release
        // A producer's namespace this suite does not know travels along, member for member.
        val withForeign = withRelease(v110) { release ->
            (release.get("metadata") as ObjectNode).putObject("org.example.producer").put("batch", 7)
        }
        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = withForeign, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            // A release below it is received beside it, and keeps its own id too.
            ImportCatalogZip(tenantKey = b, zipBytes = v100, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            val received = recorded(b, catalog, "1.1.0")!!
            assertThat(received.versionKey).isEqualTo(sent.releaseId)
            assertThat(received.baseVersion).isEqualTo("1.0.0")
            assertThat(received.baseFingerprint).isEqualTo(sent.base!!.fingerprint)
            assertThat(received.renderingDefaults).isEqualTo(RenderingDefaults.CURRENT.version)
            assertThat(recorded(b, catalog, "1.0.0")!!.versionKey).isEqualTo(manifestOf(v100).release.releaseId)

            val again = manifestOf(ExportCatalogZip(testCatalog(b, catalog), "1.1.0").execute().zipBytes).release
            assertThat(again.releaseId).isEqualTo(sent.releaseId)
            assertThat(again.base).isEqualTo(sent.base)
            assertThat(again.metadata).isEqualTo(sent.metadata!! + ("org.example.producer" to mapOf("batch" to 7)))
            assertThat(again.fingerprint).isEqualTo(sent.fingerprint)
        }
        assertThat(recorded(a, catalog, "1.1.0")!!.baseFingerprint).isEqualTo(sent.base!!.fingerprint)
    }

    @Test
    fun `a release whose id another release of the catalog has here is refused, the same release is not`() {
        val (_, catalog, v100, v110) = lineage("Release Id Taken")
        val b = createTenant("Release Id Taken B").id
        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = v110, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            // The same release again is the release the tenant has.
            assertThat(ImportCatalogZip(tenantKey = b, zipBytes = v110, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute().newlyInstalled).isFalse()

            val taken = withRelease(v100) { it.put("releaseId", manifestOf(v110).release.releaseId) }
            assertThatThrownBy { ImportCatalogZip(tenantKey = b, zipBytes = taken, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
                .isInstanceOf(CatalogImportConflictException::class.java)
                .hasMessageContaining("one id names one release")
        }
        assertThat(recorded(b, catalog, "1.0.0")).isNull()
    }

    @Test
    fun `a release that renders with rendering defaults this installation lacks is refused, an older known one is kept`() {
        val (_, catalog, v100, v110) = lineage("Rendering Defaults")
        val b = createTenant("Rendering Defaults B").id
        fun renderingWith(bytes: ByteArray, version: Int) = withRelease(bytes) { release ->
            (release.get("metadata").get("app.epistola.suite") as ObjectNode).put("renderingDefaultsVersion", version)
        }
        withMediator {
            assertThatThrownBy { ImportCatalogZip(tenantKey = b, zipBytes = renderingWith(v110, RenderingDefaults.CURRENT.version + 1), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
                .isInstanceOf(ReleaseRenderingDefaultsTooNewException::class.java)
                .hasMessageContaining("Upgrade Epistola to receive this release")
            assertThat(GetCatalog(testCatalog(b, catalog)).query()).isNull()

            ImportCatalogZip(tenantKey = b, zipBytes = renderingWith(v110, 5), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            assertThat(recorded(b, catalog, "1.1.0")!!.renderingDefaults).isEqualTo(5)
            // Without metadata a release renders with this installation's current defaults, as before.
            ImportCatalogZip(tenantKey = b, zipBytes = withRelease(v100) { it.remove("metadata") }, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            assertThat(recorded(b, catalog, "1.0.0")!!.renderingDefaults).isEqualTo(RenderingDefaults.CURRENT.version)
        }
    }
}
