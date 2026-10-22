// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ExportAssets
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A release's binaries are read by the hash its resources name, and only what a release retained answers. */
class GetReleasedBinaryTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun png(rgb: Int): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, rgb) }, "png", out)
        out.toByteArray()
    }

    @Test
    fun `the bytes of a released image are read by its content hash`() {
        val tenant = withMediator { createTenant("released-binary").id }
        val bytes = png(0x112233)
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            UploadAsset(tenant, "Logo", AssetMediaType.PNG, bytes, 1, 1, letters, id = AssetKey.of("logo")).execute()
            mediator.releaseNext(CatalogId(letters, TenantId(tenant)))
        }

        val image = withMediator { GetReleasedResource(tenant, letters, "1.0.0", "image", "logo").query() } as ImageResource
        assertThat(withMediator { GetReleasedBinary(tenant, image.contentHash).query() }).isEqualTo(bytes)
    }

    @Test
    fun `a hash no release retained answers nothing, even when the working copy has it`() {
        val tenant = withMediator { createTenant("released-binary-none").id }
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            UploadAsset(tenant, "Logo", AssetMediaType.PNG, png(0x445566), 1, 1, letters, id = AssetKey.of("logo")).execute()
        }
        // The working copy names the same hash a release would.
        val hash = withMediator { ExportAssets(tenant, catalogKey = letters).query() }.single().contentHash

        assertThat(withMediator { GetReleasedBinary(tenant, hash).query() }).isNull()
        assertThat(withMediator { GetReleasedBinary(tenant, "0".repeat(64)).query() }).isNull()
    }
}
