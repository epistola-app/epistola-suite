// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NodeIdentityTest {

    @Test
    fun `uses the configured node id when set`() {
        assertThat(NodeIdentity("node-7").nodeId).isEqualTo("node-7")
    }

    @Test
    fun `blank or null configured id falls back to a non-blank identifier`() {
        // No explicit id → resolves through the env / hostname chain, which
        // always yields a non-blank value (worst case the literal "unknown").
        assertThat(NodeIdentity("   ").nodeId).isNotBlank()
        assertThat(NodeIdentity(null).nodeId).isNotBlank()
    }
}
