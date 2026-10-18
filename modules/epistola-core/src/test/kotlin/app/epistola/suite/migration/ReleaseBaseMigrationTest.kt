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
 * `core_release_base` on data shaped like an installation before it: every release is kept as it
 * was, with no base (a released draft's base cannot be recovered), and a release can record one.
 *
 * Seeded with raw SQL on purpose: commands speak only the current schema, and this fixture must
 * match the schema as it was before this migration.
 */
@Tag("integration")
class ReleaseBaseMigrationTest {

    @Test
    fun `existing releases are kept without a base, and a release can record one`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "release_base_migration"
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
        DriverManager.getConnection(url, postgres.username, postgres.password).use(::seed)
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(rows(connection, RELEASES)).containsExactly(
                listOf("1.0.0", "v5:" + "a".repeat(64), "First", null),
                listOf("1.0.1", "v5:" + "a".repeat(64), "Second", null),
            )

            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, notes, manifest_snapshot, base_version)
                    SELECT 'acme', catalog_id, '1.1.0', 'v5:' || repeat('a', 64), 'Third', '{}', '1.0.0' FROM catalogs WHERE catalog_key = 'letters'
                    """.trimIndent(),
                )
            }
            assertThat(rows(connection, RELEASES).last()).containsExactly("1.1.0", "v5:" + "a".repeat(64), "Third", "1.0.0")
        }
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type)
                    VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED');
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, notes, manifest_snapshot)
                    SELECT 'acme', catalog_id, v, 'v5:' || repeat('a', 64), n, '{}'
                    FROM catalogs, (VALUES ('1.0.0', 'First'), ('1.0.1', 'Second')) AS versions(v, n) WHERE catalog_key = 'letters';
                """.trimIndent(),
            )
        }
    }

    private fun rows(connection: Connection, sql: String): List<List<String?>> = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map { rs.getString(it) }) }
        }
    }

    private companion object {
        /** The last migration before `core_release_base`. */
        const val BEFORE = "20261018200100"

        const val RELEASES = "SELECT version, fingerprint, notes, base_version FROM catalog_releases ORDER BY version"
    }
}
