// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.queries

import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GetServerInfoHandlerIT : IntegrationTestBase() {

    @Test
    fun `returns non-empty serverVersion, apiVersion, and nodeId`() {
        val info = withMediator { GetServerInfo().query() }

        // serverVersion comes from BuildProperties when available, else "dev".
        assertThat(info.serverVersion).isNotBlank
        // apiVersion is read from the contract JAR's Implementation-Version manifest
        // entry. Asserting only that it's non-blank — the exact version is owned by
        // the contract module, not this test, and bumps shouldn't ripple here.
        assertThat(info.apiVersion).isNotBlank
        assertThat(info.apiVersion).doesNotContain(" ")
        // nodeId always resolves to *something* (env override → HOSTNAME → hostname).
        assertThat(info.nodeId).isNotBlank
    }

    @Test
    fun `is idempotent — repeated calls return identical info`() {
        val first = withMediator { GetServerInfo().query() }
        val second = withMediator { GetServerInfo().query() }
        assertThat(second).isEqualTo(first)
    }
}
