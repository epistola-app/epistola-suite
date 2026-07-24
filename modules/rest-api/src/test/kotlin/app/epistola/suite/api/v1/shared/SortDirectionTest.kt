// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SortDirectionTest {
    @Test
    fun `known values map to their direction, case-insensitively`() {
        assertThat(SortDirection.fromParam("asc")).isEqualTo(SortDirection.ASC)
        assertThat(SortDirection.fromParam("desc")).isEqualTo(SortDirection.DESC)
        assertThat(SortDirection.fromParam("ASC")).isEqualTo(SortDirection.ASC)
        assertThat(SortDirection.fromParam("Desc")).isEqualTo(SortDirection.DESC)
    }

    @Test
    fun `an absent (null) direction selects the DESC default`() {
        // Absent is not an error; the contract default is desc.
        assertThat(SortDirection.fromParam(null)).isEqualTo(SortDirection.DESC)
    }

    @Test
    fun `unknown and blank values are rejected rather than falling back`() {
        // Mirrors the sort key: a malformed direction fails loudly rather than being
        // silently reinterpreted as the default. The REST layer maps this to a 400.
        listOf("up", "asc ", "").forEach { bad ->
            assertThatThrownBy { SortDirection.fromParam(bad) }
                .isInstanceOf(UnsupportedSortDirectionException::class.java)
        }
    }

    @Test
    fun `the rejection enumerates the supported directions`() {
        assertThatThrownBy { SortDirection.fromParam("sideways") }
            .isInstanceOfSatisfying(UnsupportedSortDirectionException::class.java) {
                assertThat(it.value).isEqualTo("sideways")
                assertThat(it.supportedValues).containsExactly("asc", "desc")
            }
    }

    @Test
    fun `paramValues lists the supported directions in declaration order`() {
        assertThat(SortDirection.paramValues).containsExactly("asc", "desc")
    }

    @Test
    fun `descending flag matches the direction`() {
        assertThat(SortDirection.ASC.descending).isFalse()
        assertThat(SortDirection.DESC.descending).isTrue()
    }
}
