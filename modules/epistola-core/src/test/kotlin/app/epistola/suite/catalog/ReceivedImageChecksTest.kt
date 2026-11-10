// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withReleaseFingerprint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * A release recorded beside the one a catalog has installed (ADR 0032: older than it) is stored as
 * published, without passing through an upload. Its images are checked all the same: an SVG that
 * reaches outside, or a raster image declaring more pixels than a render can decode, is refused and
 * nothing of the release is recorded.
 */
class ReceivedImageChecksTest : IntegrationTestBase() {

    private val key = CatalogKey.of("received-images")

    /** B has 2.0.0 installed, with an ordinary image. */
    private fun installed(): TenantKey {
        val tenant = createTenant("Received images").id
        withMediator { import(tenant, archive("2.0.0", "image/png", realPng())) }
        return tenant
    }

    private fun import(tenant: TenantKey, zip: ByteArray) = ImportCatalogZip(tenantKey = tenant, zipBytes = zip, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

    private fun versions(tenant: TenantKey) = withMediator { ListCatalogReleaseSummaries(testCatalog(tenant, key)).query().map { it.version } }

    @Test
    fun `an older release whose image declares too many pixels is not recorded`() {
        val tenant = installed()

        val refused = withMediator { catchThrowable { import(tenant, archive("1.0.0", "image/png", pngClaiming(50_000, 50_000))) } }

        assertThat(refused).hasMessageContaining("50000 × 50000")
        assertThat(versions(tenant)).containsExactly("2.0.0")
    }

    @Test
    fun `an older release whose SVG reaches outside is not recorded`() {
        val tenant = installed()
        val unsafe = """<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><image href="http://169.254.169.254/latest/meta-data" width="96" height="96"/></svg>"""

        val refused = withMediator { catchThrowable { import(tenant, archive("1.0.0", "image/svg+xml", unsafe.toByteArray())) } }

        assertThat(refused).hasMessageContaining("SVG not accepted")
        assertThat(versions(tenant)).containsExactly("2.0.0")
    }

    @Test
    fun `an older release with ordinary images is recorded beside the installed one`() {
        val tenant = installed()

        withMediator { import(tenant, archive("1.0.0", "image/png", realPng(width = 20))) }

        assertThat(versions(tenant)).containsExactlyInAnyOrder("1.0.0", "2.0.0")
    }

    /** A wire-v8 archive of [version] holding one image of [mediaType] with [content]. */
    private fun archive(version: String, mediaType: String, content: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val manifest = """
            {"schemaVersion":8,
             "catalog":{"registry":"example.org","namespace":"acme","key":"${key.value}","name":"Received images","description":null,"attributes":[],"keywords":[],"presentation":null,"license":null},
             "publisher":{"name":"Test","url":null},
             "release":{"version":"$version","releasedAt":null,"fingerprint":null},
             "compatibility":null,"includes":null,"dependencies":[],
             "resources":[{"type":"image","key":"mark","name":"Mark","description":null,"updatedAt":null,
                           "detailUrl":"./resources/image/mark.json"}]}
        """.trimIndent()
        val detail = """
            {"schemaVersion":8,"resource":{"key":"mark","name":"Mark","mediaType":"$mediaType","width":96,"height":96,
             "contentHash":"$hash","type":"image"}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf("catalog.json" to manifest.toByteArray(), "resources/image/mark.json" to detail.toByteArray(), "bin/$hash" to content).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return withReleaseFingerprint(out.toByteArray())
    }

    private fun realPng(width: Int = 10) = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, 10, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    /** A PNG that is only a header claiming [width] × [height]: a few dozen bytes. */
    private fun pngClaiming(width: Int, height: Int): ByteArray {
        val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        val chunk = ByteBuffer.allocate(4 + 13).put("IHDR".toByteArray()).put(ihdr).array()
        val crc = CRC32().apply { update(chunk) }.value.toInt()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) +
            ByteBuffer.allocate(4).putInt(13).array() + chunk + ByteBuffer.allocate(4).putInt(crc).array()
    }
}
