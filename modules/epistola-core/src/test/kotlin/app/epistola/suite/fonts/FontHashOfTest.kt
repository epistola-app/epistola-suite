// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.fonts.model.sha256Hex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [FontStore.hashOf] remembers a hash per array, so it must stay the hash of each array's own bytes. */
class FontHashOfTest {

    @Test
    fun `each array hashes to its own bytes, also when another with the same bytes was hashed first`() {
        val first = ByteArray(1024) { 7 }
        val same = first.copyOf()
        val other = ByteArray(1024) { 8 }

        assertThat(FontStore.hashOf(first)).isEqualTo(sha256Hex(first))
        assertThat(FontStore.hashOf(same)).isEqualTo(sha256Hex(first))
        assertThat(FontStore.hashOf(other)).isEqualTo(sha256Hex(other)).isNotEqualTo(FontStore.hashOf(first))
        assertThat(FontStore.hashOf(first)).isEqualTo(sha256Hex(first))
    }
}
