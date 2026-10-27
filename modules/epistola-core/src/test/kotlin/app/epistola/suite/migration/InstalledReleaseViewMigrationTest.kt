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
 * `core_installed_release_view`: the release a catalog installed is the received release its next
 * version descends from, read from its rows; the `installed_*` pointer and the trigger that moved the
 * next version with it go.
 *
 * Seeded with raw SQL on purpose: the fixture must hold the columns the migration removes, which no
 * command writes any more.
 */
@Tag("integration")
class InstalledReleaseViewMigrationTest {

    @Test
    fun `the installed release is the received one the next version is based on, and the pointer goes`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "installed_release_view_migration"
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

        val hash = "v6:${"1".repeat(64)}"
        flyway(BEFORE).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                -- Received 2.0.0, which it holds as a release; the trigger based its next version on it by number.
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, source_url, installed_release_version, installed_fingerprint)
                VALUES ('received', 'acme', 'exchange.epistola.app', 'other', 'Received', 'exchange:other/received', '2.0.0', 'publisher-said');
                INSERT INTO catalog_versions (tenant_key, catalog_id, state, origin, version, version_key, fingerprint, content_hash, released_at)
                SELECT tenant_key, catalog_id, 'released', 'installed', '2.0.0', 'received-2', '$hash', '$hash', NOW() FROM catalogs WHERE catalog_key = 'received';
                -- The pointer names a release it does not hold.
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, source_url, installed_release_version)
                VALUES ('dangling', 'acme', 'exchange.epistola.app', 'other', 'Dangling', 'exchange:other/dangling', '1.0.0');
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT c.catalog_key, ir.version, ir.fingerprint FROM catalog_installed_releases ir JOIN catalogs c ON c.catalog_id = ir.catalog_id ORDER BY 1"))
                .`as`("the release it holds, with the fingerprint recorded on it; none for a pointer to nothing")
                .containsExactly(listOf("received", "2.0.0", hash))
            assertThat(
                connection.rows(
                    """
                    SELECT n.base_version_id = r.version_id FROM catalogs c
                    JOIN catalog_versions n ON n.version_id = c.next_version_id
                    JOIN catalog_versions r ON r.catalog_id = c.catalog_id AND r.state = 'released'
                    WHERE c.catalog_key = 'received'
                    """,
                ),
            ).`as`("the next version names its base by its row").containsExactly(listOf("t"))
            assertThat(
                connection.rows(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'catalogs' AND column_name LIKE 'installed%'
                    """,
                ),
            ).`as`("the pointer columns are gone").isEmpty()

            // A catalog inserted now opens its next version on no release.
            connection.exec("INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('fresh', 'acme', 'exchange.epistola.app', 'acme', 'Fresh')")
            assertThat(
                connection.rows(
                    "SELECT n.state, n.base_version, n.base_version_id FROM catalogs c JOIN catalog_versions n ON n.version_id = c.next_version_id WHERE c.catalog_key = 'fresh'",
                ),
            ).containsExactly(listOf("draft", null, null))
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
        /** The migration before `core_installed_release_view`. */
        const val BEFORE = "20261026203611"
    }
}
