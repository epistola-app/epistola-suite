// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.assets.AssetIntegrationTest
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CatalogImportValidationException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A received archive whose binary does not hash to the `contentHash` its resource declares is
 * refused (contract `2.0.0-alpha.3`, #101): the hash is a binary's identity, so a catalog cannot
 * claim to carry bytes it does not.
 */
class TamperedArchiveTest : IntegrationTestBase() {

    private val catalog = CatalogKey.of("tampered")

    @Test
    fun `importing an archive whose image bytes do not match their hash is refused with the mismatch`() {
        val a = createTenant("Tampered A").id
        val b = createTenant("Tampered B").id
        val archive = withMediator {
            val id = testCatalog(a, catalog)
            CreateCatalog(tenantKey = a, id = catalog, name = "Tampered").execute()
            UploadAsset(id, "logo", AssetMediaType.PNG, AssetIntegrationTest.createMinimalPng(), 1, 1).execute()
            MarkCatalogReady(id).execute()
            ReleaseCatalogDraft(catalog = id, version = "1.0.0").execute()
            ExportCatalogZip(id, "1.0.0").execute().zipBytes
        }
        val entries = entries(archive)
        val binary = entries.keys.single { it.startsWith("bin/") }
        // Other bytes under the same name: the manifest and the image still declare the original hash.
        entries[binary] = entries.getValue(binary) + byteArrayOf(0)

        val refused = withMediator { catchThrowableOfType(CatalogImportValidationException::class.java) { ImportCatalogZip(tenantKey = b, zipBytes = zip(entries)).execute() } }

        assertThat(refused).isNotNull
        assertThat(refused.findings.map { it.code }).contains("CATALOG_ASSET_CONTENT_HASH_MISMATCH")
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
