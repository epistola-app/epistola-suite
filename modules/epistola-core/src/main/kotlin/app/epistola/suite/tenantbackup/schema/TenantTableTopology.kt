package app.epistola.suite.tenantbackup.schema

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
 * Two explicit lists ([INCLUDE] / [DENY_TENANT_TABLES]) overlay discovery. A discovered
 * tenant-scoped table in **neither** list fails [resolve] loudly: a future migration that adds a
 * tenant-scoped table forces a conscious include/exclude decision. This is the drift guard that
 * keeps "auto-adapts to migrations" honest (asserted by `TenantTableTopologyDriftTest`).
 */
@Component
class TenantTableTopology {
    /**
     * Resolves the topology against the live schema. Throws if a tenant-scoped table is
     * unclassified (drift) or if the INCLUDE tables contain an FK cycle we do not special-case.
     */
    fun resolve(handle: Handle): TenantTopology {
        val tenantScoped = discoverTenantScopedTables(handle)

        val unclassified = tenantScoped - INCLUDE - DENY_TENANT_TABLES
        require(unclassified.isEmpty()) {
            "Tenant-scoped table(s) ${unclassified.sorted()} are not classified for backup. Add each to " +
                "TenantTableTopology.INCLUDE (backed up) or DENY_TENANT_TABLES (excluded). See docs/tenant-backup.md."
        }

        val present = INCLUDE.filter { tenantScoped.contains(it) || it == TENANTS }.toSet()
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

        /** The asset-blob key prefix in `content_store`; blobs are dumped/restored separately. */
        fun assetBlobPrefix(tenantKey: String): String = "assets/$tenantKey/"

        /**
         * Tenant-scoped tables that ARE backed up and merge-restored, in any order (FK order is
         * derived at runtime). `tenants` is included but update-in-place (never deleted).
         */
        val INCLUDE: Set<String> =
            setOf(
                "tenants",
                "catalogs",
                "catalog_releases",
                "themes",
                "document_templates",
                "template_variants",
                "template_versions",
                "contract_versions",
                "stencils",
                "stencil_versions",
                "code_lists",
                "code_list_entries",
                "fonts",
                "font_variants",
                "assets",
                "variant_attribute_definitions",
                "environments",
                "environment_activations",
                "api_keys",
                "feature_toggles",
                "feedback",
                "feedback_comments",
                "feedback_assets",
            )

        /**
         * Tenant-scoped tables that are deliberately NOT backed up and NEVER touched by restore —
         * generated documents (regenerable), the append-only collect feed and its cursors (must
         * survive and stay monotonic for external consumers), audit/runtime/membership tables.
         */
        val DENY_TENANT_TABLES: Set<String> =
            setOf(
                "documents",
                "document_generation_requests",
                "document_generation_batches",
                "generation_results",
                "consumer_partition_cursors",
                "consumer_node_assignments",
                "event_log",
                "application_log",
                "tenant_memberships",
                "cluster_timers",
                "cluster_tasks_scheduled",
                "load_test_runs",
                "feedback_sync_config",
                // The local backup store itself — never back up the backups (would be recursive).
                "tenant_backups",
            )
    }
}
