// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ReleaseBase
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
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
 * A release says what it is wherever it goes (catalog wire v8): its id, the rendering defaults it
 * renders with, and the release it was made from, in its snapshot and in every archive of it, none of
 * it moving its fingerprint.
 */
class ReleaseDescriptionTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), CatalogId(catalog, TenantId(tenant)))

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
        h.createQuery("SELECT manifest_snapshot::text FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
            .bind("t", tenant).bind("c", catalog).bind("v", version)
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
            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = "1.0.0").execute()
            UpdateTheme(theme(a, catalog, "brand"), name = "Brand 2").execute()
            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = "1.1.0").execute()

            val releases = ListCatalogVersions(a, catalog).query().filter { it.status == CatalogVersionStatus.RELEASED }.associateBy { it.version }
            val first = manifestOf(ExportCatalogZip(a, catalog, "1.0.0").execute().zipBytes).release
            val second = manifestOf(ExportCatalogZip(a, catalog, "1.1.0").execute().zipBytes).release
            val metadata = mapOf("app.epistola.suite" to mapOf("renderingDefaultsVersion" to RenderingDefaults.CURRENT.version))

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
            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = "1.0.0").execute()
            UpdateTheme(theme(a, catalog, "brand"), name = "Brand 2").execute()
            MarkCatalogReady(a, catalog).execute()
            val released = ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = "1.1.0").execute()
            val archive = ExportCatalogZip(a, catalog, "1.1.0").execute().zipBytes
            val bare = withRelease(archive) { it.remove(listOf("releaseId", "metadata", "base")) }

            fun fingerprintOf(bytes: ByteArray) = CatalogArchiveReader.read(ByteArrayInputStream(bytes)).archive!!.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }
            assertThat(manifestOf(archive).release.base).isNotNull()
            assertThat(fingerprintOf(archive)).isEqualTo(released.fingerprint).isEqualTo(fingerprintOf(bare))
            assertThat(manifestOf(archive).release.fingerprint).isEqualTo(released.fingerprint)
        }
    }
}
