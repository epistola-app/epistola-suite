package app.epistola.suite.loadtest

import app.epistola.suite.backup.TenantBackupTableContributor
import org.springframework.stereotype.Component

/**
 * Declares the load-test feature's tenant-scoped `load_test_runs` table as
 * **excluded** from tenant backup/restore — it is transient diagnostic/runtime
 * data, not authoring data. The backup topology collects this contribution, so the
 * load-test module owns the classification of its own table.
 */
@Component
class LoadTestBackupTables : TenantBackupTableContributor {
    override fun excludedTables(): Set<String> = setOf("load_test_runs")
}
