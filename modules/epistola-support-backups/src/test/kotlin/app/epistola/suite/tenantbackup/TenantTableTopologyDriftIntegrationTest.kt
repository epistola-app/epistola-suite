package app.epistola.suite.tenantbackup

import app.epistola.suite.tenantbackup.schema.TenantTableTopology
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The drift guard that keeps "auto-adapts to migrations" honest: every tenant-scoped table in the
 * live schema must be classified INCLUDE (backed up) or DENY (excluded). A migration that adds a
 * tenant-scoped table fails this test until a developer makes a conscious decision.
 */
class TenantTableTopologyDriftIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Autowired
    lateinit var topology: TenantTableTopology

    @Test
    fun `every tenant-scoped table is classified and the topology resolves`() {
        jdbi.useHandle<Exception> { handle ->
            val discovered = topology.discoverTenantScopedTables(handle)
            val classified = TenantTableTopology.INCLUDE + TenantTableTopology.DENY_TENANT_TABLES
            assertThat(discovered - classified)
                .withFailMessage(
                    "Unclassified tenant-scoped table(s): %s. Add each to TenantTableTopology.INCLUDE or DENY_TENANT_TABLES.",
                    discovered - classified,
                ).isEmpty()

            // Resolving must not throw (no FK cycle outside the special-cased tenants↔themes edge).
            assertThat(topology.resolve(handle).orderedTables).isNotEmpty()
        }
    }
}
