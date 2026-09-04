// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The guard on the generic array mapper.
 *
 * It is registered on the erased `List`, which means it is the last thing between a column of some
 * other element type and a field declared `List<String>`. It cannot see the declared type, so it
 * refuses anything that is not already a string rather than coercing it into one that would look
 * correct and mean something else.
 */
class SqlArrayAsStringListTest {
    @Test
    fun `string elements pass through in order`() {
        assertThat(sqlArrayAsStringList("scopes", arrayOf("read", "publish"))).containsExactly("read", "publish")
    }

    @Test
    fun `an empty array is an empty list`() {
        assertThat(sqlArrayAsStringList("scopes", emptyArray<String>())).isEmpty()
    }

    @Test
    fun `a non-string element is refused, naming the column and the type`() {
        val id = UUID.randomUUID()

        assertThatThrownBy { sqlArrayAsStringList("owner_ids", arrayOf(id)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("owner_ids")
            .hasMessageContaining("java.util.UUID")
    }

    @Test
    fun `a null element is refused rather than becoming a hole in a list of non-null strings`() {
        // `List<String>` promises no nulls; letting one through produces a list that violates its
        // own type and fails somewhere else with nothing pointing back here.
        assertThatThrownBy { sqlArrayAsStringList("scopes", arrayOf("read", null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("scopes")
    }
}
