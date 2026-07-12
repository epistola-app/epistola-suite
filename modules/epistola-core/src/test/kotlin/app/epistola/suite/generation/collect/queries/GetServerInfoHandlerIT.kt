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
        // apiVersion is reported by the contract library via ServerContractInfo
        // (built into the contract JAR from the OpenAPI spec's info.version). The
        // exact version is owned by the contract module — bumps shouldn't ripple
        // here — but it must resolve to a real version, never the "unknown"
        // fallback that means the contract couldn't be identified on the classpath.
        assertThat(info.apiVersion).isNotBlank
        assertThat(info.apiVersion).doesNotContain(" ")
        assertThat(info.apiVersion).isNotEqualTo("unknown")
        assertThat(info.apiVersion).matches("""\d+\.\d+\.\d+.*""")
        // minApiVersion is the compatibility floor, derived from the same contract
        // library (ServerContractInfo.minCompatibleContractVersion). Like apiVersion
        // it must resolve to a real version, never the "unknown" fallback — it's the
        // lower bound of the accepted client range [minApiVersion .. apiVersion].
        assertThat(info.minApiVersion).isNotBlank
        assertThat(info.minApiVersion).isNotEqualTo("unknown")
        assertThat(info.minApiVersion).matches("""\d+\.\d+\.\d+.*""")
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
