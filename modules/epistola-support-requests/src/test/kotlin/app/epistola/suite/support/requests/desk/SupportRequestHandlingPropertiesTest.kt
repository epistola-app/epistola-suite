// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.desk

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `epistola.support.requests.handled-by`: the inbox, or a support connection by its endpoint; no keyword. */
class SupportRequestHandlingPropertiesTest {
    @Test
    fun `inbox or an endpoint, and nothing else`() {
        assertThat(SupportRequestHandlingProperties().endpoint).isNull()
        assertThat(SupportRequestHandlingProperties("INBOX").endpoint).isNull()
        assertThat(SupportRequestHandlingProperties("https://support.epistola.app/").endpoint).isEqualTo("https://support.epistola.app")
        assertThatThrownBy { SupportRequestHandlingProperties("epistola") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
