// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit

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
