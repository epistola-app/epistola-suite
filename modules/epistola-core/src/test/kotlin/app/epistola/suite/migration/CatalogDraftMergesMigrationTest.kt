// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `core_catalog_draft_merges`: the releases a draft recorded in `catalog_versions.merged_releases`
 * become rows of `catalog_draft_merges`, moved rather than lost, and the next version moved to a
 * release an install puts in place has merged nothing on it.
 *
 * Seeded with raw SQL on purpose: the fixture must hold the column the migration removes, which no
 * command writes any more.
 */
@Tag("integration")
class CatalogDraftMergesMigrationTest {

    @Test
    fun `every merged release a draft recorded becomes a merge row, and moving the next version clears its merges`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "catalog_draft_merges_migration"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$database\"")
                it.execute("CREATE DATABASE \"$database\"")
            }
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        fun flyway(target: String?) = Flyway.configure()
            .dataSource(url, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(target) }
            .load()

        flyway(BEFORE).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, source_url, installed_release_version)
                VALUES ('received', 'acme', 'epistola.app', 'other', 'Received', 'exchange:other/received', '1.0.0');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('made', 'acme', 'epistola.app', 'acme', 'Made');
                UPDATE catalog_versions SET merged_releases = ARRAY['1.1.0', '1.0.1'] WHERE is_next
                    AND catalog_id = (SELECT catalog_id FROM catalogs WHERE catalog_key = 'received');
                """.trimIndent(),
            )
        }
        // Up to the migration that replaced this trigger (core_installed_release_view).
        flyway(UNTIL).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            fun merges() = connection.rows(
                "SELECT c.catalog_key, m.release_version, m.merge_base, m.resolutions::text FROM catalog_draft_merges m " +
                    "JOIN catalog_versions v ON v.tenant_key = m.tenant_key AND v.version_id = m.version_id " +
                    "JOIN catalogs c ON c.catalog_id = v.catalog_id ORDER BY 1, 2",
            )
            assertThat(merges()).`as`("moved, not lost").containsExactly(
                listOf("received", "1.0.1", null, "{}"),
                listOf("received", "1.1.0", null, "{}"),
            )
            assertThat(connection.rows("SELECT count(*) FROM information_schema.columns WHERE table_name = 'catalog_versions' AND column_name = 'merged_releases'"))
                .`as`("the column is gone once its values moved")
                .containsExactly(listOf("0"))

            connection.exec("UPDATE catalogs SET installed_release_version = '2.0.0' WHERE catalog_key = 'received'")
            assertThat(merges()).`as`("on its new base the next version has merged nothing").isEmpty()
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** Every row of [sql], each column as text, nulls kept. */
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map { rs.getString(it) }) }
        }
    }

    private companion object {
        /** The last migration whose trigger moved the next version with `installed_release_version`. */
        const val UNTIL = "20261026203611"

        /** The migration before `core_catalog_draft_merges`. */
        const val BEFORE = "20261021081527"
    }
}
