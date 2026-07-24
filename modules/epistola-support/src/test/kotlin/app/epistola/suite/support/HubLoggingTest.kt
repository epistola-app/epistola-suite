// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.hub.client.error.HubInternalException
import app.epistola.hub.client.error.HubUnauthenticatedException
import app.epistola.hub.client.error.HubUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HubLoggingTest {
    @Test
    fun `isHubUnreachable is true for a direct HubUnavailableException`() {
        assertThat(HubUnavailableException("hub down").isHubUnreachable()).isTrue()
    }

    @Test
    fun `isHubUnreachable is true when the unavailable exception is wrapped`() {
        val wrapped = RuntimeException("snapshot failed", HubUnavailableException("hub down"))
        assertThat(wrapped.isHubUnreachable()).isTrue()
    }

    @Test
    fun `isHubUnreachable is false for other hub exceptions`() {
        assertThat(HubInternalException("boom").isHubUnreachable()).isFalse()
        assertThat(HubUnauthenticatedException("bad key").isHubUnreachable()).isFalse()
    }

    @Test
    fun `isHubUnreachable is false for non-hub exceptions`() {
        assertThat(IllegalStateException("plain bug").isHubUnreachable()).isFalse()
    }

    @Test
    fun `hubCause finds the first HubException in the cause chain`() {
        val hub = HubUnavailableException("hub down")
        val outer = IllegalStateException("a", RuntimeException("b", hub))
        assertThat(outer.hubCause()).isSameAs(hub)
    }

    @Test
    fun `hubCause is null when no HubException is present`() {
        assertThat(RuntimeException("a", IllegalStateException("b")).hubCause()).isNull()
    }
}
