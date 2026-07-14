package app.epistola.suite.tenantbackup.schema

import app.epistola.suite.backup.TenantBackupTableContributor
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/** One column's type metadata, captured from `information_schema.columns` at dump time. */
data class ColumnMeta(
    val name: String,
    val dataType: String,
    val udtName: String,
    val nullable: Boolean,
)

/** A tenant-scoped table to dump/restore: its columns (in ordinal order) and primary-key columns. */
data class TableSpec(
    val table: String,
    val columns: List<ColumnMeta>,
    val primaryKey: List<String>,
)

/** The resolved, FK-ordered set of tenant-scoped tables plus the asset-blob key prefix. */
data class TenantTopology(
    /** INCLUDE tables in FK insert order (every parent before its children). */
    val orderedTables: List<TableSpec>,
)

/**
 * Discovers the tenant-scoped tables to back up and the order to restore them in, by reading
 * `information_schema` at runtime — so a schema migration that adds a column (or a whole table) is
 * captured automatically without touching this code. Only genuine special cases are hand-coded
 * (the `tenants`↔`themes` cycle is broken here; the asset-blob set and the merge mechanics live in
 * the dump/restore services).
 *
 * Each module classifies its own tenant tables as included/excluded via a
 * [TenantBackupTableContributor] bean, which this topology aggregates over discovery. A discovered
 * tenant-scoped table that **no** contributor classifies fails [resolve] loudly: a future migration
 * that adds a tenant-scoped table forces a conscious include/exclude decision. This is the drift guard
 * that keeps "auto-adapts to migrations" honest (asserted by `TenantTableTopologyDriftIntegrationTest`
 * for the local context, and `TenantBackupClassificationAppTest` for the full app composition).
 */
@Component
class TenantTableTopology(
    private val contributors: List<TenantBackupTableContributor> = emptyList(),
) {
    /** Every contributing module's included tenant tables (those backed up and merge-restored). */
    fun includedTables(): Set<String> = contributors.flatMapTo(mutableSetOf()) { it.includedTables() }

    /** Every contributing module's excluded tenant tables (never backed up or restored). */
    fun excludedTables(): Set<String> = contributors.flatMapTo(mutableSetOf()) { it.excludedTables() }

    /**
     * Resolves the topology against the live schema. Throws if a tenant-scoped table is
     * unclassified (drift) or if the INCLUDE tables contain an FK cycle we do not special-case.
     */
    fun resolve(handle: Handle): TenantTopology {
        val tenantScoped = discoverTenantScopedTables(handle)
        val include = includedTables()
        val exclude = excludedTables()

        // A table declared both included and excluded (a contributor conflict, or one bean listing it
        // twice) would silently back up something meant to be excluded — fail instead of guessing.
        val conflicting = include intersect exclude
        require(conflicting.isEmpty()) {
            "Tenant table(s) ${conflicting.sorted()} are declared both included and excluded by " +
                "TenantBackupTableContributor beans. Each table must be classified exactly once."
        }

        val unclassified = tenantScoped - include - exclude
        require(unclassified.isEmpty()) {
            "Tenant-scoped table(s) ${unclassified.sorted()} are not classified for backup. The owning module " +
                "must declare each via a TenantBackupTableContributor (included or excluded). See docs/tenant-backup.md."
        }

        val present = include.filter { tenantScoped.contains(it) || it == TENANTS }.toSet()
        val columns = loadColumns(handle, present)
        val primaryKeys = loadPrimaryKeys(handle, present)
        val edges = loadForeignKeyEdges(handle, present)

        val ordered = topologicalOrder(present, edges)
        val specs =
            ordered.map { table ->
                TableSpec(
                    table = table,
                    columns = columns[table] ?: error("No columns found for table $table"),
                    primaryKey = primaryKeys[table] ?: error("No primary key found for table $table"),
                )
            }
        return TenantTopology(orderedTables = specs)
    }

    /**
     * Every non-partition base table in `public` that has a `tenant_key` column, plus `tenants`
     * itself. Partition **children** (e.g. `documents_2026_06`) inherit `tenant_key` from their
     * parent but are excluded — only the partitioned parent is classified.
     */
    fun discoverTenantScopedTables(handle: Handle): Set<String> {
        val withTenantKey =
            handle
                .createQuery(
                    "SELECT c.relname FROM pg_class c " +
                        "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition " +
                        "AND EXISTS (SELECT 1 FROM information_schema.columns col " +
                        "  WHERE col.table_schema = 'public' AND col.table_name = c.relname " +
                        "  AND col.column_name = 'tenant_key')",
                ).mapTo(String::class.java)
                .set()
        return withTenantKey + TENANTS
    }

    private fun loadColumns(
        handle: Handle,
        tables: Set<String>,
    ): Map<String, List<ColumnMeta>> = handle
        .createQuery(
            "SELECT table_name, column_name, data_type, udt_name, is_nullable, ordinal_position " +
                "FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name IN (<tables>) " +
                "ORDER BY table_name, ordinal_position",
        ).bindList("tables", tables.toList())
        .map { rs, _ ->
            rs.getString("table_name") to
                ColumnMeta(
                    name = rs.getString("column_name"),
                    dataType = rs.getString("data_type"),
                    udtName = rs.getString("udt_name"),
                    nullable = rs.getString("is_nullable") == "YES",
                )
        }.list()
        .groupBy({ it.first }, { it.second })

    private fun loadPrimaryKeys(
        handle: Handle,
        tables: Set<String>,
    ): Map<String, List<String>> = handle
        .createQuery(
            "SELECT tc.table_name, kcu.column_name, kcu.ordinal_position " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = 'public' " +
                "  AND tc.table_name IN (<tables>) " +
                "ORDER BY tc.table_name, kcu.ordinal_position",
        ).bindList("tables", tables.toList())
        .map { rs, _ -> rs.getString("table_name") to rs.getString("column_name") }
        .list()
        .groupBy({ it.first }, { it.second })

    /**
     * FK edges among the INCLUDE tables as `child dependsOn parent` pairs. Self-edges and the
     * `tenants`→`themes` default-theme edge are dropped (the latter is the cycle we break by nulling
     * `default_theme_key` during restore).
     */
    private fun loadForeignKeyEdges(
        handle: Handle,
        tables: Set<String>,
    ): Set<Pair<String, String>> = handle
        .createQuery(
            "SELECT DISTINCT tc.table_name AS child, ccu.table_name AS parent " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.constraint_column_usage ccu " +
                "  ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' " +
                "  AND tc.table_name IN (<tables>) AND ccu.table_name IN (<tables>)",
        ).bindList("tables", tables.toList())
        .map { rs, _ -> rs.getString("child") to rs.getString("parent") }
        .list()
        .filter { (child, parent) -> child != parent && !(child == TENANTS && parent == THEMES) }
        .toSet()

    /**
     * Orders tables so every FK parent precedes its children (Kahn, deterministic by name). Throws
     * on a cycle that is not one of the special-cased edges.
     */
    private fun topologicalOrder(
        nodes: Set<String>,
        edges: Set<Pair<String, String>>,
    ): List<String> {
        // child -> set of parents that must come first
        val parents = nodes.associateWith { mutableSetOf<String>() }.toMutableMap()
        edges.forEach { (child, parent) -> if (child in nodes && parent in nodes) parents[child]!!.add(parent) }

        val remaining = nodes.toMutableSet()
        val ordered = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val next =
                remaining.filter { parents[it]!!.all { p -> p !in remaining } }.minOrNull()
                    ?: error("Cyclic foreign keys among backup tables: ${remaining.sorted()}")
            ordered += next
            remaining -= next
        }
        return ordered
    }

    companion object {
        const val TENANTS = "tenants"
        const val THEMES = "themes"

        // Asset blobs are dumped/restored separately (see DumpTenantTables /
        // MergeRestoreTables) from the content-addressable `asset_content` store,
        // resolved through each asset's `content_hash` pointer (#738).

        // "What is in a backup" is a data-fidelity and security decision. It is declared **per module**
        // via TenantBackupTableContributor beans — each module classifies its own tenant tables
        // (included / excluded), and this topology only aggregates and FK-orders them. The drift guard
        // ensures every discovered tenant table is classified by some contributor. Core's tables live in
        // CoreBackupTables; this module's own tenant_backups in BackupsOwnTables; feature modules ship
        // their own (epistola-audit, feedback, loadtest).
    }
}
