// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withReleaseFingerprint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A catalog declaring an image dependency is refused until that image is installed -- and found
 * when it is.
 *
 * Wire v7 qualifies an image dependency with its catalog, because an image slug became readable
 * and two catalogs may each hold a `logo`. The importer's probe has to look the dependency up by
 * *both* halves. It did not: the probe was rewritten to yield `image:<catalog>:<slug>` while the
 * check still asked for `asset:<slug>`, so every qualified image dependency read as missing and no
 * catalog declaring one could install. Nothing caught it, because no test exercised the probe at
 * all.
 */
class ImageDependencyProbeTest : IntegrationTestBase() {
    @Test
    fun `an image dependency is found when the image is installed in the named catalog`() {
        val tenant = createTenant("Image Dep Present")

        withMediator {
            CreateCatalog(tenant.id, BRAND, "Brand").execute()
            UploadAsset(
                catalog = testCatalog(tenant.id, BRAND),
                name = "Logo",
                mediaType = AssetMediaType.PNG,
                content = PNG,
                width = 1,
                height = 1,
                id = AssetKey.of("logo"),
            ).execute()
            // A wire-v8 archive pins a release of every catalog it declares: brand's is cut first.
            val brandVersion = mediator.releaseNext(testCatalog(tenant.id, BRAND))
            val brandFingerprint = ListCatalogReleaseSummaries(testCatalog(tenant.id, BRAND)).query().single { it.version == brandVersion }.fingerprint
            val brand = requireNotNull(GetCatalog(testCatalog(tenant.id, BRAND)).query())

            ImportCatalogZip(
                tenantKey = tenant.id,
                zipBytes = archiveDependingOnImage(BRAND.value, brandVersion, brandFingerprint, registry = brand.registry, namespace = brand.namespace),
                provenance = ReleaseProvenance(ReleaseChannel.UPLOAD),
            ).execute()

            assertThat(GetCatalog(testCatalog(tenant.id, CatalogKey.of("letters"))).query()).isNotNull()
        }
    }

    @Test
    fun `an image dependency in a catalog the tenant does not have is refused by name`() {
        val tenant = createTenant("Image Dep Missing")

        withMediator {
            CreateCatalog(tenant.id, BRAND, "Brand").execute()
            // The image exists, but in `brand`; the archive names `house`.
            UploadAsset(
                catalog = testCatalog(tenant.id, BRAND),
                name = "Logo",
                mediaType = AssetMediaType.PNG,
                content = PNG,
                width = 1,
                height = 1,
                id = AssetKey.of("logo"),
            ).execute()

            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenant.id,
                    zipBytes = archiveDependingOnImage("house"),
                    provenance = ReleaseProvenance(ReleaseChannel.UPLOAD),
                ).execute()
            }
                // The release it pins is not to be had here.
                .isInstanceOf(CatalogImportDependenciesMissingException::class.java)
                .hasMessageContaining("house")

            // Refused before anything was written.
            assertThat(GetCatalog(testCatalog(tenant.id, CatalogKey.of("letters"))).query()).isNull()
        }
    }

    /**
     * A catalog whose only content is a manifest declaring one qualified image dependency, pinned to
     * [version] of [imageCatalog] with [fingerprint], as wire v8 requires.
     */
    private fun archiveDependingOnImage(
        imageCatalog: String,
        version: String = "1.0.0",
        fingerprint: String = "v6:${"0".repeat(64)}",
        registry: String = REGISTRY,
        namespace: String = NAMESPACE,
    ): ByteArray {
        val manifest = """
            {"schemaVersion":8,
             "catalog":{"registry":"$REGISTRY","namespace":"$NAMESPACE","key":"letters","name":"Letters","description":null,"attributes":[],"keywords":[],"presentation":null,"license":null},
             "publisher":{"name":"Test","url":null},
             "release":{"version":"1.0.0","releasedAt":null,"fingerprint":null},
             "compatibility":null,"includes":null,
             "dependencies":[{"type":"image","catalogKey":"$imageCatalog","key":"logo"}],
             "catalogDependencies":[{"alias":"$imageCatalog","registry":"$registry","namespace":"$namespace","catalogKey":"$imageCatalog","releaseVersion":"$version","releaseFingerprint":"$fingerprint"}],
             "resources":[]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("catalog.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        return withReleaseFingerprint(out.toByteArray())
    }

    private companion object {
        const val REGISTRY = "exchange.epistola.app"
        const val NAMESPACE = "test-tenant"
        val BRAND = CatalogKey.of("brand")
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
        )
    }
}
