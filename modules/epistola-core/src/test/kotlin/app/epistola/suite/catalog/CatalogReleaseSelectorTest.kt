// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Selectors follow npm's range grammar and semantics (node-semver), not any other range syntax. */
@Tag("unit")
class CatalogReleaseSelectorTest {

    @ParameterizedTest(name = "{0} matches {1}: {2}")
    @CsvSource(
        "^1.4.0, 1.4.2, true",
        "^1.4.0, 1.9.0, true",
        "^1.4.0, 2.0.0, false",
        "^1.4.0, 1.3.9, false",
        "^0.2.3, 0.2.9, true",
        "^0.2.3, 0.3.0, false",
        "~1.4.0, 1.4.7, true",
        "~1.4.0, 1.5.0, false",
        ">=1.0.0 <2.0.0, 1.99.0, true",
        ">=1.0.0 <2.0.0, 2.0.0, false",
        "1.4.0, 1.4.0, true",
        "1.4.0, 1.4.1, false",
        "*, 7.3.1, true",
        "1.x, 1.8.0, true",
        "1.x, 2.0.0, false",
        "1.2.3 - 1.4, 1.4.9, true",
        "1.2.3 - 1.4, 1.5.0, false",
        "^1.0.0 || ^3.0.0, 3.1.0, true",
        "^1.0.0 || ^3.0.0, 2.1.0, false",
        // A hotfix's build metadata does not change precedence (contract ADR 0001).
        "^1.4.0, 1.4.2+hotfix.1, true",
        // A pre-release only matches a range naming a pre-release of the same version.
        "^1.4.0, 1.5.0-rc.1, false",
        "^1.5.0-rc.0, 1.5.0-rc.1, true",
        "^1.4.0, not-semver, false",
        // A patch release (ADR 0034) is a release, sorted between the core it fixes and the next.
        "^1.3.0, 1.3.1-gemeente-x.1, true",
        "~1.3.0, 1.3.1-gemeente-x.1, true",
        "~1.3.1, 1.3.1-gemeente-x.1, false",
        "1.3.1, 1.3.1-gemeente-x.1, false",
        "1.3.1-gemeente-x.1, 1.3.1-gemeente-x.1, true",
        ">=1.3.0 <1.3.1, 1.3.1-gemeente-x.1, true",
        "^2.0.0, 1.3.1-gemeente-x.1, false",
    )
    fun `matches like npm`(selector: String, version: String, expected: Boolean) {
        assertThat(CatalogReleaseSelector.parseOrNull(selector)!!.matches(version)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["[1.0,2.0)", "latest", "abc", "1.2.3.4", "^", ">= ", "1.0.0 -", "01.2.3", "   "])
    fun `rejects what is not an npm range`(selector: String) {
        assertThat(CatalogReleaseSelector.parseOrNull(selector)).isNull()
    }

    @org.junit.jupiter.api.Test
    fun `rejects a selector longer than the contract allows`() {
        assertThat(CatalogReleaseSelector.parseOrNull("^1.0.0 || ".repeat(11) + "^1.0.0")).isNull()
    }

    @org.junit.jupiter.api.Test
    fun `highest match picks by precedence, not by text`() {
        val selector = CatalogReleaseSelector.parseOrNull("^1.0.0")!!

        assertThat(selector.highestMatch(listOf("1.9.0", "1.10.0", "2.0.0", "1.2.0"))).isEqualTo("1.10.0")
        assertThat(selector.highestMatch(listOf("2.0.0"))).isNull()
    }

    @org.junit.jupiter.api.Test
    fun `a patch release is picked above the release it fixes and below the next one`() {
        val selector = CatalogReleaseSelector.parseOrNull("^1.3.0")!!

        assertThat(selector.highestMatch(listOf("1.3.0", "1.3.1-gemeente-x.1"))).isEqualTo("1.3.1-gemeente-x.1")
        assertThat(selector.highestMatch(listOf("1.3.0", "1.3.1-gemeente-x.1", "1.3.1"))).isEqualTo("1.3.1")
        assertThat(selector.highestMatch(listOf("1.3.1-gemeente-x.1", "1.3.1-gemeente-x.2", "1.3.1-bureau.9"))).isEqualTo("1.3.1-gemeente-x.2")
    }
}
