package app.epistola.suite.partitions

/**
 * Declares a native RANGE-partitioned (by month, on a timestamp column) table that
 * `PartitionMaintenanceScheduler` should keep partitions for.
 *
 * @property tableName the partitioned parent table
 * @property retentionMonths months of history to keep; `null` means **keep forever**
 *   (partitions are created but never auto-dropped — e.g. the audit log)
 */
data class PartitionedTable(
    val tableName: String,
    val retentionMonths: Int?,
)

/**
 * SPI: a module contributes the partitioned tables it owns. `PartitionMaintenanceScheduler`
 * collects every contributor bean and maintains the union — so a feature module
 * (e.g. `epistola-audit`) declares its own partitioned tables without the scheduler
 * (or core) hard-coding their names. Mirrors the `NavContributor` pattern.
 */
interface PartitionedTableContributor {
    fun partitionedTables(): List<PartitionedTable>
}
