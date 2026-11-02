// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.system

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SystemCatalogSetup
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * A test tenant whose system catalog is copied from the template ([app.epistola.suite.testing.SystemCatalogTemplate])
 * holds what a tenant that installed it holds: the same rows in every tenant-scoped table, compared
 * without what differs between any two tenants (their key, ids, timestamps). When installing the
 * system catalog writes something new, or writes it differently, this fails, and the copy follows.
 */
class SystemCatalogTemplateTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `a tenant with the template's system catalog holds what a tenant that installed it holds`() {
        val installed = createTenant("Installed", SystemCatalogSetup.INSTALL).id
        val copied = createTenant("Copied", SystemCatalogSetup.FROM_TEMPLATE).id

        val installedRows = rowsOf(installed)
        val copiedRows = rowsOf(copied)

        assertThat(copiedRows.keys).containsExactlyInAnyOrderElementsOf(installedRows.keys)
        for (table in installedRows.keys) {
            assertThat(copiedRows.getValue(table)).describedAs(table).containsExactlyInAnyOrderElementsOf(installedRows.getValue(table))
        }
    }

    /** Every tenant-scoped table's rows of [tenant], as comparable strings, keyed by table; tables without any left out. */
    private fun rowsOf(tenant: TenantKey): Map<String, List<String>> = jdbi.withHandle<Map<String, List<String>>, Exception> { handle ->
        tenantTables(handle).mapNotNull { (table, ignored) ->
            val rows = handle.createQuery("SELECT to_jsonb(t)::text FROM $table t WHERE tenant_key = :t")
                .bind("t", tenant.value).mapTo(String::class.java).list()
                .map { json ->
                    val row = objectMapper.readTree(json) as ObjectNode
                    (ignored + "tenant_key").forEach(row::remove)
                    // A version's key is drawn at random when it is made (a release's is its releaseId,
                    // in its snapshot too), so it is another value on every install.
                    val versionKey = row.remove("version_key")?.asString()
                    objectMapper.writeValueAsString(row)
                        .replace(tenant.value, "<tenant>")
                        .let { if (versionKey == null) it else it.replace(versionKey, "<version-key>") }
                        .replace(INSTANT, "<instant>")
                }
            if (rows.isEmpty()) null else table to rows.sorted()
        }.toMap()
    }

    /** The tenant-scoped tables (not their partitions), each with the columns that differ between any two tenants. */
    private fun tenantTables(handle: org.jdbi.v3.core.Handle): List<Pair<String, Set<String>>> = handle.createQuery(
        """
        SELECT c.relname AS table_name,
               COALESCE(array_agg(a.attname::text) FILTER (WHERE format_type(a.atttypid, NULL) IN ('uuid', 'timestamp with time zone', 'timestamp without time zone')), '{}') AS ignored
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition
          AND EXISTS (SELECT 1 FROM pg_attribute k WHERE k.attrelid = c.oid AND k.attname = 'tenant_key' AND NOT k.attisdropped)
        GROUP BY c.relname
        ORDER BY c.relname
        """,
    ).map { rs, _ -> rs.getString("table_name") to (rs.getArray("ignored").array as Array<*>).map { it.toString() }.toSet() }.list()

    private companion object {
        /** A moment written into JSON (a release's `releasedAt`): when it happened, not what it is. */
        val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")
    }
}
