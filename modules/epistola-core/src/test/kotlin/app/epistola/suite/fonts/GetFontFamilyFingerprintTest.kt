// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.GetFontFamilyFingerprint
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

/**
 * Unit-of-behaviour coverage for [GetFontFamilyFingerprint]: the digest is
 * stable across repeated calls for an unchanged family, and flips when a face's
 * bytes change (delete+re-upload under the same slug/weight/italic with a
 * different binary). This is the primitive the fail-loud-on-mismatch publish
 * pin and render verification are built on.
 */
class GetFontFamilyFingerprintTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    private fun bytesOf(resource: String): ByteArray = resourceLoader
        .getResource("classpath:$resource")
        .contentAsByteArray

    private fun upload(tenant: TenantId, name: String, resource: String): AssetKey = UploadAsset(
        tenantId = tenant.key,
        name = name,
        mediaType = AssetMediaType.TTF,
        content = bytesOf(resource),
        width = null,
        height = null,
        catalogKey = CatalogKey.DEFAULT,
    ).execute().id

    @Test
    fun `fingerprint is stable across calls and non-null when faces exist`() {
        withMediator {
            val tenant = createTenant("FP Stable Tenant")
            val tenantId = TenantId(tenant.id)
            val regular = upload(tenantId, "fp-reg.ttf", "epistola/fonts/inter/inter-Regular.ttf")

            ImportFont(
                tenantId = tenantId,
                catalogKey = CatalogKey.DEFAULT,
                slug = "fp-font",
                name = "FP Font",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = regular)),
            ).execute()

            val slug = FontKey.of("fp-font")
            val first = GetFontFamilyFingerprint(tenant.id, CatalogKey.DEFAULT, slug).query()
            val second = GetFontFamilyFingerprint(tenant.id, CatalogKey.DEFAULT, slug).query()

            assertThat(first).isNotNull()
            assertThat(second).isEqualTo(first)

            // A family with no rows at all has nothing to pin → null.
            assertThat(
                GetFontFamilyFingerprint(tenant.id, CatalogKey.DEFAULT, FontKey.of("no-such-font")).query(),
            ).isNull()
        }
    }

    @Test
    fun `fingerprint changes when a face's bytes change`() {
        withMediator {
            val tenant = createTenant("FP Change Tenant")
            val tenantId = TenantId(tenant.id)
            val slug = FontKey.of("fp-mut")

            val regular = upload(tenantId, "fp-mut-1.ttf", "epistola/fonts/inter/inter-Regular.ttf")
            ImportFont(
                tenantId = tenantId,
                catalogKey = CatalogKey.DEFAULT,
                slug = slug.value,
                name = "FP Mut",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = regular)),
            ).execute()
            val before = GetFontFamilyFingerprint(tenant.id, CatalogKey.DEFAULT, slug).query()

            // Re-import the SAME face key (400, false) but a DIFFERENT binary —
            // ImportFont delete-and-reinserts variants, re-hashing the bytes.
            val replacement = upload(tenantId, "fp-mut-2.ttf", "epistola/fonts/inter/inter-Bold.ttf")
            ImportFont(
                tenantId = tenantId,
                catalogKey = CatalogKey.DEFAULT,
                slug = slug.value,
                name = "FP Mut",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = replacement)),
            ).execute()
            val after = GetFontFamilyFingerprint(tenant.id, CatalogKey.DEFAULT, slug).query()

            assertThat(before).isNotNull()
            assertThat(after).isNotNull()
            assertThat(after).isNotEqualTo(before)
        }
    }
}
