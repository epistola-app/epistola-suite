package app.epistola.suite.tenantbackup

import app.epistola.suite.tenantbackup.schema.TenantTableTopology
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
            val classified = topology.includedTables() + topology.excludedTables()
            assertThat(discovered - classified)
                .withFailMessage(
                    "Unclassified tenant-scoped table(s): %s. The owning module must declare each via a TenantBackupTableContributor.",
                    discovered - classified,
                ).isEmpty()

            // Resolving must not throw (no FK cycle outside the special-cased tenants↔themes edge).
            assertThat(topology.resolve(handle).orderedTables).isNotEmpty()
        }
    }

    @Test
    fun `an unclassified tenant-scoped table trips the drift guard`() {
        // Prove the guard actually fires (not just that today's schema happens to be clean): a new
        // tenant-scoped table in neither list must be discovered and must make resolve() refuse.
        // Done in a rolled-back transaction so it leaves no trace.
        jdbi.useHandle<Exception> { handle ->
            handle.begin()
            try {
                handle.execute("CREATE TABLE drift_probe (tenant_key text NOT NULL, id int NOT NULL, PRIMARY KEY (tenant_key, id))")

                assertThat(topology.discoverTenantScopedTables(handle)).contains("drift_probe")
                assertThatThrownBy { topology.resolve(handle) }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("not classified")
            } finally {
                handle.rollback()
            }
        }
    }
}
