// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseTargeting
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * A render resolves a font family in the version it renders. Two drafts of a catalog can give the
 * same family different files (parallel work replacing the house font); the bytes a render embeds
 * must be its own version's, however recently the other version was rendered.
 */
class FontBytesPerVersionTest : IntegrationTestBase() {

    @Autowired private lateinit var resourceLoader: ResourceLoader

    @Autowired private lateinit var targeting: CatalogReleaseTargeting

    @Autowired private lateinit var transactions: TransactionTemplate

    private val letters = CatalogKey.of("letters")

    private fun font(name: String) = resourceLoader.getResource("classpath:fonts/$name").contentAsByteArray

    private fun houseSans(tenant: TenantKey, file: String, version: CatalogReleaseRef?) = withMediator {
        val asset = UploadAsset(testCatalog(tenant, letters), "$file", AssetMediaType.TTF, font(file), null, null, catalogVersion = version).execute().id
        ImportFont(
            catalog = testCatalog(TenantId(tenant), letters),
            slug = "house-sans",
            name = "House Sans",
            kind = FontKind.SANS.wire,
            variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = asset)),
            catalogVersion = version,
        ).execute()
    }

    /** The bytes a render of [draft] embeds for House Sans 400. */
    private fun renderedIn(tenant: TenantKey, draft: UUID): ByteArray? = withMediator {
        transactions.execute {
            targeting.within(draft) { fontFamilyResolver(testCatalog(tenant, letters), dependencies = app.epistola.suite.catalog.dependencies.DraftDependencyView.live(testCatalog(tenant, letters))).resolve(null, "house-sans", 400, false)?.bytes }
        }
    }

    @Test
    fun `each draft's render gets its own version of a font family`() {
        val tenant = createTenant("Font per version").id
        withMediator { CreateCatalog(tenant, letters, "Letters").execute() }
        houseSans(tenant, "LiberationSans-Bold.ttf", version = null)
        val workKey = withMediator { CreateCatalogDraft(testCatalog(tenant, letters), name = "New house font").execute().versionId }
        houseSans(tenant, "LiberationSans-Regular.ttf", CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(workKey)))
        val work = withMediator { targeting.resolve(CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(workKey))) }
        val next = withMediator { targeting.resolve(CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Next)) }

        assertThat(renderedIn(tenant, next)).isEqualTo(font("LiberationSans-Bold.ttf"))
        assertThat(renderedIn(tenant, work)).`as`("right after the next version's render").isEqualTo(font("LiberationSans-Regular.ttf"))
        assertThat(renderedIn(tenant, next)).isEqualTo(font("LiberationSans-Bold.ttf"))
    }
}
