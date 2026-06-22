package app.epistola.suite.tenantbackup

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenantbackup.schema.TenantTableTopology
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Drift guard at the **app** level, where every feature module is composed together.
 *
 * `TenantTableTopologyDriftIntegrationTest` runs in the backups module's own context, which doesn't
 * include feature modules like feedback/loadtest — so their tenant tables aren't even present there.
 * This test boots the full app and asserts every module's tenant-scoped tables are classified by a
 * [app.epistola.suite.backup.TenantBackupTableContributor] (so a module that adds a tenant table
 * without classifying it fails the build here).
 */
class TenantBackupClassificationAppTest : BaseIntegrationTest() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Autowired
    lateinit var topology: TenantTableTopology

    @Test
    fun `every module's tenant-scoped tables are classified in the full app`() {
        jdbi.useHandle<Exception> { handle ->
            // Sanity: the cross-module tenant tables really are present in the full composition.
            val discovered = topology.discoverTenantScopedTables(handle)
            assertThat(discovered).contains("feedback", "feedback_comments", "feedback_assets", "load_test_runs")

            // Resolving must not throw — every discovered tenant table is classified, the cross-module
            // ones via their owning module's contributor bean.
            val backedUp = topology.resolve(handle).orderedTables.map { it.table }.toSet()

            // load_test_runs is excluded; feedback (included) IS backed up.
            assertThat(backedUp).doesNotContain("load_test_runs")
            assertThat(backedUp).contains("feedback", "feedback_comments", "feedback_assets")
        }
    }
}
