// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `core_release_resources_by_version_row` (ADR 0036, step (b)): a release's resources name its row of
 * `catalog_versions`, not its number, which leaves the table; read as before, they still give the
 * release's number.
 *
 * Seeded with raw SQL on purpose: the fixture must match the schema before the migration.
 */
@Tag("integration")
class ReleaseResourcesByVersionRowMigrationTest {

    @Test
    fun `a release's resources name its row and still read with its number`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "release_resources_by_version_row_migration"
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

        val hash = "1".repeat(64)
        flyway(BEFORE_RESOURCES_BY_VERSION_ROW).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');
                INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES ('acme', '$hash', 'theme', '{"resource": {"key": "brand"}}'::jsonb);
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, fingerprint, content_hash, manifest_snapshot)
                SELECT tenant_key, catalog_id, '1.0.0', 'first', 'v6:$hash', 'v6:$hash', '{}'::jsonb FROM catalogs;
                INSERT INTO release_resources (tenant_key, catalog_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name)
                SELECT tenant_key, catalog_id, '1.0.0', 'theme', 'brand', '01a17908-1369-73cc-a8b4-00000000b0a0', '$hash', 'v6:$hash', 'Brand' FROM catalogs;
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT v.version, v.state FROM release_resources r JOIN catalog_versions v ON v.version_id = r.version_id"))
                .`as`("the release's row").containsExactly(listOf("1.0.0", "released"))
            assertThat(connection.rows("SELECT version, resource_key FROM release_wire_resources"))
                .`as`("read with its number, as before").containsExactly(listOf("1.0.0", "brand"))
            assertThatThrownBy { connection.rows("SELECT version FROM release_resources") }.hasMessageContaining("version")
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
