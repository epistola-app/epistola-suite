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
        // apiVersion is the contract version this build targets — defaults to 0.3.0.
        assertThat(info.apiVersion).isEqualTo("0.3.0")
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
