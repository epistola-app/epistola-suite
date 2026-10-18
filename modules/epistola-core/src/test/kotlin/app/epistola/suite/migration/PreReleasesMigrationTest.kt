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
 * `core_pre_releases` gives a pre-release (`2.0.0-redesign.1`) the sort keys of its number and says it
 * is one, and leaves every release as it was: same keys, and no pre-release part.
 */
@Tag("integration")
class PreReleasesMigrationTest {

    @Test
    fun `releases keep their sort keys, and a pre-release gets its number and its identifiers`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "pre_releases_migration"
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
            // Releases as they exist before pre-releases; no command writes this shape on its own.
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type) VALUES
                    ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED');
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, manifest_snapshot)
                SELECT 'acme', catalog_id, v, 'v5:' || repeat('0', 64), '{}' FROM catalogs, unnest(ARRAY['1.0.0', '1.10.0', '5.5']) v;
                """.trimIndent(),
            )
        }

        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                "INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, manifest_snapshot) " +
                    "SELECT 'acme', catalog_id, '2.0.0-redesign-2027.1', 'v5:' || repeat('1', 64), '{}' FROM catalogs",
            )
            val rows = connection.createStatement().use { st ->
                st.executeQuery("SELECT version, version_major, version_minor, version_patch, version_pre_release FROM catalog_releases ORDER BY version").use { rs ->
                    buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))) }
                }
            }
            assertThat(rows).containsExactly(
                listOf("1.0.0", "1", "0", "0", null),
                listOf("1.10.0", "1", "10", "0", null),
                listOf("2.0.0-redesign-2027.1", "2", "0", "0", "redesign-2027.1"),
                listOf("5.5", null, null, null, null),
            )
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private companion object {
        /** The last migration before the one under test. */
        const val BEFORE = "20261018131409"
    }
}
