// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.schema

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * What the migrated schema must look like, asserted against `pg_catalog` rather than by reading the
 * migrations that produced it.
 *
 * Runs at the **app** level, where every module's migrations are merged onto the one global Flyway
 * namespace — the same reason `TenantBackupClassificationAppTest` lives here. A core-module context
 * never sees `quality_findings` or `load_test_runs`, so it cannot check them.
 *
 * Two kinds of assertion, and the split matters:
 *
 *  - **Hygiene** — invariants that should hold of any healthy schema, whatever the feature. These
 *    are the ones with a life beyond the change that prompted them: they fail on a mistake nobody
 *    thought to look for. Both were written after a review found the corresponding defect by hand.
 *  - **Model** — the shape the catalog-resource identity migrations claim to produce. A migration
 *    that half-applies, or a later one that quietly undoes part of it, fails here rather than at
 *    runtime.
 */
class SchemaHygieneAppTest : BaseIntegrationTest() {
    @Autowired
    lateinit var jdbi: Jdbi

    /** The seven types a catalog resource can be, each keyed by its identity. */
    private val resourceTables = listOf(
        "assets",
        "code_lists",
        "document_templates",
        "fonts",
        "stencils",
        "themes",
        "variant_attribute_definitions",
    )

    // ---------------------------------------------------------------- hygiene

    /**
     * Two indexes over the same columns of the same table are pure cost: every write maintains
     * both, and which one a foreign key binds to is decided by creation order rather than by
     * anything a reader can see.
     *
     * This is not hypothetical. Six of these shipped in an earlier revision of this branch — each
     * re-keyed table kept a `UNIQUE (tenant_key, resource_id)` that became an exact copy of its
     * primary key once the key swapped, and every foreign key bound to the copy. It was found by
     * querying `pg_constraint` by hand; this is that query, kept.
     */
    @Test
    fun `no table carries two indexes over the same columns`() {
        val duplicates = jdbi.withHandle<List<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT table_name || ': ' || string_agg(index_name, ' = ' ORDER BY index_name)
                             || '  (' || columns || ')' AS duplicate
                    FROM (
                        SELECT c.relname AS table_name,
                               i.relname AS index_name,
                               pg_get_expr(x.indpred, x.indrelid) AS predicate,
                               (SELECT string_agg(a.attname, ', ' ORDER BY k.ord)
                                  FROM unnest(x.indkey) WITH ORDINALITY k(att, ord)
                                  JOIN pg_attribute a ON a.attrelid = x.indrelid AND a.attnum = k.att
                               ) AS columns
                        FROM pg_index x
                        JOIN pg_class c ON c.oid = x.indrelid
                        JOIN pg_class i ON i.oid = x.indexrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public'
                          -- A partition's index legitimately mirrors its parent's.
                          AND NOT c.relispartition
                    ) idx
                    GROUP BY table_name, columns, predicate
                    HAVING count(*) > 1
                    ORDER BY 1
                    """,
                )
                .mapTo(String::class.java)
                .list()
        }

        assertThat(duplicates)
            .describedAs("indexes duplicating another index's exact column list on the same table")
            .isEmpty()
    }

    /**
     * PostgreSQL truncates a generated constraint name at 63 characters, silently. A truncated name
     * is not wrong, but it is unstable: add a column to the key and the name changes, so a later
     * migration that drops it by name breaks. Anything at exactly the limit was almost certainly
     * generated rather than chosen, and should be named explicitly.
     *
     * `NOT NULL` is excluded, and the exclusion is the interesting part. PostgreSQL 18 made `NOT
     * NULL` a real catalog constraint (`contype = 'n'`) where 17 and earlier kept it as nothing but
     * `pg_attribute.attnotnull` — so raising the floor to 18 made two long-named columns appear
     * here overnight, with no schema change behind them. They are exempt because the instability
     * this test is about cannot reach them: the name is derived from one column
     * (`<table>_<column>_not_null`), so adding columns never changes it, and nothing drops one by
     * name anyway — every migration in the tree uses `SET NOT NULL` / `DROP NOT NULL`.
     */
    @Test
    fun `no constraint name sits at the truncation limit`() {
        val truncated = jdbi.withHandle<List<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT con.conrelid::regclass::text || '.' || con.conname
                    FROM pg_constraint con
                    JOIN pg_namespace n ON n.oid = con.connamespace
                    WHERE n.nspname = 'public' AND length(con.conname) >= 63
                      AND con.contype <> 'n'
                    ORDER BY 1
                    """,
                )
                .mapTo(String::class.java)
                .list()
        }

        assertThat(truncated)
            .describedAs("constraint names at PostgreSQL's 63-character limit — name these explicitly")
            .isEmpty()
    }

    /**
     * A partitioned index is only usable once every partition has attached its own; until then
     * PostgreSQL marks the parent invalid and the planner ignores it. Creating one `ON ONLY` and
     * forgetting to attach the children leaves exactly that state, and nothing else reports it.
     */
    @Test
    fun `every partitioned index has all of its partitions attached`() {
        val invalid = jdbi.withHandle<List<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT i.relname || ' on ' || c.relname
                    FROM pg_index x
                    JOIN pg_class i ON i.oid = x.indexrelid
                    JOIN pg_class c ON c.oid = x.indrelid
                    JOIN pg_namespace n ON n.oid = i.relnamespace
                    WHERE n.nspname = 'public'
                      AND i.relkind = 'I'
                      AND NOT x.indisvalid
                    ORDER BY 1
                    """,
                )
                .mapTo(String::class.java)
                .list()
        }

        assertThat(invalid)
            .describedAs("partitioned indexes missing a partition — the planner will not use these")
            .isEmpty()
    }

    // ---------------------------------------------------------------- the identity model

    /**
     * The whole point of the re-keying: a resource is identified by what it is, not by where it
     * lives. If a primary key here ever reverts to the address, relocation silently goes back to
     * rewriting every dependant.
     */
    @Test
    fun `every catalog resource is keyed by its identity, with the address kept unique`() {
        val keys = primaryKeyColumns()
        val uniques = uniqueConstraintColumns()

        resourceTables.forEach { table ->
            assertThat(keys[table])
                .describedAs("$table primary key")
                .isEqualTo(listOf("tenant_key", "resource_id"))

            assertThat(uniques[table].orEmpty())
                .describedAs("$table must still enforce its address, just not key on it")
                .anySatisfy { columns ->
                    assertThat(columns).startsWith("tenant_key", "catalog_key").hasSize(3)
                }
        }
    }

    /**
     * `ON UPDATE CASCADE` is how the interim shape carried an address change down to dependants.
     * ADR 0014 rejected it, and re-keying removed the need — so any reappearance means a dependant
     * has started storing an address again.
     */
    @Test
    fun `no foreign key into a catalog resource cascades an update`() {
        val cascading = jdbi.withHandle<List<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT con.conrelid::regclass::text || '.' || con.conname
                             || ' -> ' || referenced.relname
                    FROM pg_constraint con
                    JOIN pg_class referenced ON referenced.oid = con.confrelid
                    WHERE con.contype = 'f'
                      AND con.confupdtype = 'c'
                      AND referenced.relname IN (<resourceTables>)
                    ORDER BY 1
                    """,
                )
                .bindList("resourceTables", resourceTables)
                .mapTo(String::class.java)
                .list()
        }

        assertThat(cascading)
            .describedAs("ON UPDATE CASCADE into a catalog resource — the shape ADR 0014 rejected")
            .isEmpty()
    }

    /**
     * The dependants that used to carry a copy of their parent's address. Each is keyed by the
     * parent's identity now, and the address columns are gone — which is what makes a move a
     * single-row update. `documents` and `document_generation_requests` are deliberately absent:
     * they keep the address they recorded, as a historical fact.
     */
    @Test
    fun `owned rows name their parent's identity and no longer copy its address`() {
        val ownership = mapOf(
            "template_variants" to "template_resource_id",
            "template_versions" to "template_resource_id",
            "contract_versions" to "template_resource_id",
            "environment_activations" to "template_resource_id",
            "stencil_versions" to "stencil_resource_id",
            "code_list_entries" to "code_list_resource_id",
            "font_variants" to "font_resource_id",
            "quality_findings" to "template_resource_id",
            "load_test_runs" to "template_resource_id",
        )
        val keys = primaryKeyColumns()
        val columns = columnsByTable()

        ownership.forEach { (table, identity) ->
            assertThat(columns[table].orEmpty())
                .describedAs("$table must name its parent by identity")
                .contains(identity)

            assertThat(columns[table].orEmpty())
                .describedAs("$table must not keep a copy of its parent's address")
                .doesNotContain("catalog_key")

            // The three whose parent's address was also in their key.
            if (table in setOf("template_variants", "template_versions", "contract_versions")) {
                assertThat(keys[table])
                    .describedAs("$table primary key")
                    .contains(identity)
                    .doesNotContain("catalog_key", "template_key")
            }
        }
    }

    /**
     * Generation history is the deliberate exception, and worth pinning: it keeps the address it
     * recorded so the record still says what happened, and carries the identity only as a durable
     * link — filled forward, with no foreign key that would make a deleted template erase history.
     */
    @Test
    fun `generation history keeps its recorded address and takes no foreign key on identity`() {
        val columns = columnsByTable()

        listOf("documents", "document_generation_requests").forEach { table ->
            assertThat(columns[table].orEmpty())
                .describedAs("$table records where generation actually happened")
                .contains("catalog_key", "template_key", "template_resource_id")
        }

        val constrained = jdbi.withHandle<List<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT con.conrelid::regclass::text || '.' || con.conname
                    FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (con.conkey)
                    WHERE con.contype = 'f'
                      AND c.relname IN ('documents', 'document_generation_requests')
                      AND a.attname = 'template_resource_id'
                    """,
                )
                .mapTo(String::class.java)
                .list()
        }

        assertThat(constrained)
            .describedAs("a foreign key here would scan every partition at upgrade time, and would delete history with its template")
            .isEmpty()
    }

    // ---------------------------------------------------------------- helpers

    private fun primaryKeyColumns(): Map<String, List<String>> = constraintColumns("p")
        .mapValues { (_, byName) -> byName.values.first() }

    private fun uniqueConstraintColumns(): Map<String, Collection<List<String>>> = constraintColumns("u")
        .mapValues { (_, byName) -> byName.values }

    private fun constraintColumns(type: String): Map<String, Map<String, List<String>>> = jdbi.withHandle<Map<String, Map<String, List<String>>>, Exception> { handle ->
        handle
            .createQuery(
                """
                SELECT c.relname AS table_name, con.conname AS constraint_name, a.attname AS column_name
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY k(att, ord)
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.att
                WHERE n.nspname = 'public' AND con.contype = :type
                ORDER BY c.relname, con.conname, k.ord
                """,
            )
            .bind("type", type)
            .map { rs, _ -> Triple(rs.getString("table_name"), rs.getString("constraint_name"), rs.getString("column_name")) }
            .list()
            .groupBy { it.first }
            .mapValues { (_, rows) -> rows.groupBy({ it.second }, { it.third }) }
    }

    private fun columnsByTable(): Map<String, Set<String>> = jdbi.withHandle<Map<String, Set<String>>, Exception> { handle ->
        handle
            .createQuery(
                "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'public'",
            )
            .map { rs, _ -> rs.getString("table_name") to rs.getString("column_name") }
            .list()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, columns) -> columns.toSet() }
    }
}
