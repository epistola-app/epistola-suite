// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.catalog.protocol.CatalogInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pins the display order, because the whole point is that it no longer depends on what the stored
 * `Set` happens to iterate — and that it agrees with the order the protocol already imposes. Pure
 * logic, so no Spring.
 */
@Tag("unit")
class CatalogKeywordOrderTest {

    @Test
    fun `digits sort before letters`() {
        // The observation that started the finding: keywords beginning with a digit appeared last,
        // which is what made the order look like a broken sort rather than no sort at all.
        assertThat(listOf("brieven", "2024", "aanslag", "1-loket").inDisplayOrder())
            .containsExactly("1-loket", "2024", "aanslag", "brieven")
    }

    @Test
    fun `the order does not depend on the iteration order of the set it came from`() {
        val words = listOf("zaken", "1-loket", "brieven", "aanslag")

        assertThat(words.shuffled().toSet().inDisplayOrder())
            .isEqualTo(words.reversed().toSet().inDisplayOrder())
    }

    /**
     * The guard that matters. `CatalogInfo` holds keywords in a `TreeSet` and the canonicalizer
     * writes `keywords.sorted()`, so the protocol has already decided the order. If this screen
     * sorted differently, the catalog page and the catalog's own manifest would list the same
     * keywords in different orders — the kind of split the whole report is about.
     */
    @Test
    fun `display order is the order the catalog protocol puts keywords in`() {
        val keywords = setOf("zaken", "1-loket", "Brieven", "aanslag", "2024")

        val onTheWire = CatalogInfo.create(
            slug = "order",
            name = "Order",
            keywords = keywords,
        ).keywords.toList()

        assertThat(keywords.inDisplayOrder()).isEqualTo(onTheWire)
    }
}
