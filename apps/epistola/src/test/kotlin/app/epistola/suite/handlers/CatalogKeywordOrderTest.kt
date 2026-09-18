// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pins the display order, because the whole point is that it no longer depends on what the stored
 * `Set` happens to iterate. Pure logic, so no Spring.
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
    fun `letters sort case-insensitively`() {
        assertThat(listOf("Zaken", "aanslag", "Brieven").inDisplayOrder())
            .containsExactly("aanslag", "Brieven", "Zaken")
    }

    @Test
    fun `two spellings of one word keep a stable order between renders`() {
        // Keyword uniqueness is case-sensitive, so both can be present. Without the final tiebreak
        // these compare equal and may swap places between two renders of the same set.
        val forwards = listOf("alpha", "Alpha").inDisplayOrder()
        val backwards = listOf("Alpha", "alpha").inDisplayOrder()

        assertThat(forwards).isEqualTo(backwards)
        assertThat(forwards).containsExactly("Alpha", "alpha")
    }

    @Test
    fun `the order does not depend on the iteration order of the set it came from`() {
        val words = listOf("zaken", "1-loket", "Brieven", "aanslag")

        assertThat(words.shuffled().toSet().inDisplayOrder())
            .isEqualTo(words.reversed().toSet().inDisplayOrder())
    }
}
