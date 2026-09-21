// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Every seeded asset type is an image or a font, because two SQL predicates assume it.
 *
 * `AssetMediaType.category` splits on the `image/` and `font/` prefixes, and two queries repeat
 * that split in SQL rather than joining a column: `ListImagePage` (what `/images` lists) and
 * `ExportAssets` (what a catalog publishes as an image resource). `asset_types` is deliberately
 * extensible by INSERT and carries no category, so a third kind -- `application/pdf`, say -- would
 * be `OTHER` in Kotlin and silently invisible to both: absent from `/images`, and dropped from
 * every export without a finding.
 *
 * Seeding one is a reasonable thing to want. This fails when it happens, so that whoever does it
 * decides what the two queries should say rather than discovering it from a missing file in a
 * published catalog.
 */
class SeededAssetTypeCategoryTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `every seeded asset type is an image or a font`() {
        val seeded = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT media_type FROM asset_types ORDER BY media_type")
                .mapTo(String::class.java)
                .list()
        }

        assertThat(seeded).isNotEmpty()
        assertThat(seeded.filter { AssetMediaType(it).category == AssetMediaCategory.OTHER })
            .describedAs("seeded asset types that are neither image nor font; see this test's KDoc")
            .isEmpty()
    }
}
