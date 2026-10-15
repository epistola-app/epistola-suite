// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.generation.pdf.FontProgramCache
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

/**
 * The renderer caches parsed fonts under the content hash [ResolveFontFace] returns, without hashing
 * the bytes again. A hash that did not match its bytes would let one font's parsed program stand in
 * for another's, so both kinds of face must carry the SHA-256 of exactly the bytes returned.
 */
class ResolveFontFaceContentHashTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Test
    fun `a bundled system face carries the hash of its bytes`() {
        val tenant = createTenant("Font hash system")

        val face = withMediator { ResolveFontFace(tenant.id, SYSTEM_CATALOG_KEY, FontKey.of("lora"), 700, italic = true).query() }

        assertThat(face).isNotNull()
        assertThat(face!!.contentHash).isEqualTo(FontProgramCache.contentHash(face.content))
    }

    @Test
    fun `an uploaded face carries the hash of its bytes`() {
        val tenant = createTenant("Font hash upload")
        val letters = CatalogKey.of("letters")
        val upload = resourceLoader.getResource("classpath:fonts/LiberationSans-Bold.ttf").contentAsByteArray

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            val asset = UploadAsset(
                tenantId = tenant.id,
                name = "house-sans-bold.ttf",
                mediaType = AssetMediaType.TTF,
                content = upload,
                width = null,
                height = null,
                catalogKey = letters,
            ).execute().id
            ImportFont(
                tenantId = TenantId(tenant.id),
                catalogKey = letters,
                slug = "house-sans",
                name = "House Sans",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(700, false, FontVariantSource.ASSET, assetKey = asset)),
            ).execute()
        }

        val face = withMediator { ResolveFontFace(tenant.id, letters, FontKey.of("house-sans"), 700, italic = false).query() }

        assertThat(face).isNotNull()
        assertThat(face!!.content).isEqualTo(upload)
        assertThat(face.contentHash).isEqualTo(FontProgramCache.contentHash(upload))
    }
}
