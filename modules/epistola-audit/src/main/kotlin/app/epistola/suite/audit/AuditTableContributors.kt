// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit

import app.epistola.suite.backup.TenantBackupTableContributor
import app.epistola.suite.partitions.PartitionedTable
import app.epistola.suite.partitions.PartitionedTableContributor
import org.springframework.stereotype.Component

/**
 * Declares `audit_log` as a monthly RANGE-partitioned table retained **forever**
 * (`retentionMonths = null` → partitions created monthly, never auto-dropped).
 * The audit module owns this declaration; `PartitionMaintenanceScheduler` collects it.
 */
@Component
class AuditPartitionedTables : PartitionedTableContributor {
    override fun partitionedTables(): List<PartitionedTable> = listOf(
        PartitionedTable(tableName = "audit_log", retentionMonths = null),
    )
}

/**
 * Declares `audit_log` as **excluded** from tenant backup/restore — the audit trail
 * is append-only runtime data that must survive (and never be rewritten by) a tenant
 * restore. The backup topology collects this contribution.
 */
@Component
class AuditBackupTables : TenantBackupTableContributor {
    override fun excludedTables(): Set<String> = setOf("audit_log")
}
