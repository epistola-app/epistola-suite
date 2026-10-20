// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.security.LocalUserProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalAccountHintsTest {

    private val users = listOf(LocalUserProperties(username = "admin@local", password = "admin", displayName = "Local Admin"))

    @Test
    fun `the local profile names its development accounts`() {
        assertThat(localAccountHints(setOf("demo", "local", "localauth"), users))
            .containsExactly(LocalAccountHint("admin@local", "admin", "Local Admin"))
    }

    @Test
    fun `anywhere else configured users are real credentials, so none are shown`() {
        assertThat(localAccountHints(setOf("demo", "localauth"), users)).isEmpty()
        assertThat(localAccountHints(emptySet(), users)).isEmpty()
    }
}
