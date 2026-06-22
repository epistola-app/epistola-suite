package app.epistola.suite.backup

/**
 * SPI: a feature module declares how its own tenant-scoped tables are treated by
 * tenant backup/restore. The backup topology collects every contributor bean and
 * unions the results with its own hand-maintained classification — so a module
 * (e.g. `epistola-audit`) classifies its tables without the backup module
 * hard-coding their names. Mirrors the `NavContributor` pattern, and is the
 * evolution the topology's own notes called for once a second feature module
 * contributes tenant tables.
 *
 * Every tenant-scoped table must be classified (the drift guard enforces this), so
 * a contributing module returns each of its tenant tables in exactly one of the two
 * sets.
 */
interface TenantBackupTableContributor {
    /** Tenant-scoped tables this module owns that ARE backed up and merge-restored. */
    fun includedTables(): Set<String> = emptySet()

    /** Tenant-scoped tables this module owns that are deliberately EXCLUDED from backup/restore. */
    fun excludedTables(): Set<String> = emptySet()
}
