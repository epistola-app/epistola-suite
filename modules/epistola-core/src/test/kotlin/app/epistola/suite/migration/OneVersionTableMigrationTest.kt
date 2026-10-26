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
 * `core_one_version_table` (ADR 0036, step (a)): every release becomes a released row of
 * `catalog_versions`, keeping its id, number and record, received ones as `installed`; a release made
 * from another names it by row; the catalog's next draft is `catalogs.next_version_id`; and a release
 * recorded afterwards is mirrored as a row.
 *
 * Seeded with raw SQL on purpose: the fixture must match the schema before the migration.
 */
@Tag("integration")
class OneVersionTableMigrationTest {

    @Test
    fun `releases become released rows of catalog_versions, and the next draft becomes the catalog's pointer`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "one_version_table_migration"
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

        val here = "01a17908-1369-73cc-a8b4-aaf5d3016d37"
        val elsewhere = "01a17908-1369-73cc-a8b4-000000000001"
        flyway(BEFORE_ONE_VERSION_TABLE).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO app_metadata (key, value) VALUES ('installation', '{"id": "$here"}'::jsonb)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, fingerprint, content_hash, manifest_snapshot, released_by_installation)
                SELECT tenant_key, catalog_id, '1.0.0', 'first', 'v6:${"1".repeat(64)}', 'v6:${"1".repeat(64)}', '{}'::jsonb, '$here' FROM catalogs;
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, base_version, fingerprint, content_hash, manifest_snapshot, released_by_installation)
                SELECT tenant_key, catalog_id, '1.1.0', 'second', '1.0.0', 'v6:${"2".repeat(64)}', 'v6:${"2".repeat(64)}', '{}'::jsonb, '$elsewhere' FROM catalogs;
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT version_key, state, origin, version, fingerprint FROM catalog_versions WHERE state = 'released' ORDER BY version"))
                .containsExactly(
                    listOf("first", "released", "authored", "1.0.0", "v6:${"1".repeat(64)}"),
                    listOf("second", "released", "installed", "1.1.0", "v6:${"2".repeat(64)}"),
                )
            assertThat(connection.rows("SELECT b.version FROM catalog_versions v JOIN catalog_versions b ON b.version_id = v.base_version_id WHERE v.version = '1.1.0'"))
                .`as`("the release it was made from, by its row").containsExactly(listOf("1.0.0"))
            assertThat(connection.rows("SELECT v.is_next, v.state FROM catalogs c JOIN catalog_versions v ON v.version_id = c.next_version_id"))
                .`as`("the next draft, as the catalog's pointer").containsExactly(listOf("t", "draft"))

            // A release recorded afterwards, as installing one does, is a released row too; deleting it removes the row.
            connection.exec(
                """
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, fingerprint, content_hash, manifest_snapshot, released_by_installation)
                SELECT tenant_key, catalog_id, '2.0.0', 'third', 'v6:${"3".repeat(64)}', 'v6:${"3".repeat(64)}', '{}'::jsonb, '$elsewhere' FROM catalogs;
                """.trimIndent(),
            )
            assertThat(connection.rows("SELECT origin FROM catalog_versions WHERE version_key = 'third'")).containsExactly(listOf("installed"))
            connection.exec("DELETE FROM catalog_releases WHERE version = '2.0.0'")
            assertThat(connection.rows("SELECT count(*) FROM catalog_versions WHERE version_key = 'third'")).containsExactly(listOf("0"))
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
}
