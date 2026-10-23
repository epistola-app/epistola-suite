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
 * `core_catalogs_without_type` (ADR 0032): the suite no longer writes `catalogs.type`, so it gets a
 * default, and the database opens every catalog's next version when the catalog is inserted, not
 * only a subscribed one's. What catalogs and next versions there were stays as it was.
 *
 * Seeded with raw SQL on purpose: the fixture must match the schema before the migration, which the
 * commands no longer write (they leave `type` out), and the trigger is what is under test.
 */
@Tag("integration")
class CatalogsWithoutTypeMigrationTest {

    @Test
    fun `every inserted catalog gets exactly one next version, its type defaults, and existing data stays`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "catalogs_without_type_migration"
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
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type) VALUES ('made', 'acme', 'epistola.app', 'acme', 'Made', 'AUTHORED');
                INSERT INTO catalog_versions (tenant_key, catalog_id, kind, is_next) SELECT tenant_key, catalog_id, 'draft', TRUE FROM catalogs WHERE catalog_key = 'made';
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type, source_url, installed_release_version)
                VALUES ('received', 'acme', 'epistola.app', 'other', 'Received', 'SUBSCRIBED', 'exchange:other/received', '2.0.0');
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            fun nextVersions() = connection.rows(
                "SELECT c.catalog_key, c.type, v.base_version FROM catalog_versions v JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE v.is_next ORDER BY 1",
            )
            assertThat(nextVersions()).`as`("what was there stays").containsExactly(
                listOf("made", "AUTHORED", null),
                listOf("received", "SUBSCRIBED", "2.0.0"),
            )

            // Inserted as the suite now does: without a type, with or without a release.
            connection.exec("INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('fresh', 'acme', 'epistola.app', 'acme', 'Fresh')")
            connection.exec(
                "INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, source_url, installed_release_version) " +
                    "VALUES ('installed', 'acme', 'epistola.app', 'other', 'Installed', 'exchange:other/installed', '1.2.0')",
            )
            assertThat(connection.rows("SELECT c.catalog_key, count(v.*) FROM catalogs c LEFT JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next GROUP BY 1 ORDER BY 1"))
                .`as`("every catalog has exactly one next version")
                .containsExactly(listOf("fresh", "1"), listOf("installed", "1"), listOf("made", "1"), listOf("received", "1"))
            assertThat(nextVersions()).contains(listOf("fresh", "AUTHORED", null), listOf("installed", "AUTHORED", "1.2.0"))

            // A release put in place moves the next version of any catalog, not only a subscribed one's.
            connection.exec("UPDATE catalogs SET installed_release_version = '1.0.0' WHERE catalog_key = 'made'")
            assertThat(nextVersions()).contains(listOf("made", "AUTHORED", "1.0.0"))
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
        /** The migration before `core_catalogs_without_type`. */
        const val BEFORE = "20261021063432"
    }
}
