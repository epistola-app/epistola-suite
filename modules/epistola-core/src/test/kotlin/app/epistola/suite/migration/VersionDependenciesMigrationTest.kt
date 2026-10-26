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
 * `core_version_dependencies` (ADR 0036, step (c)): a draft's declarations and a release's pins are
 * rows of one table, by the version that declares them; a pin names the pinned release's row when it
 * is here, a declaration on another catalog's draft names that draft's row, and an alias read in a
 * release resolves through the release's pins as before.
 *
 * Seeded with raw SQL on purpose: the fixture must match the schema before the migration.
 */
@Tag("integration")
class VersionDependenciesMigrationTest {

    @Test
    fun `a release's pins and a draft's declarations share one table, naming the rows they depend on`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "version_dependencies_migration"
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
        flyway(BEFORE_VERSION_DEPENDENCIES).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES
                    ('parts', 'acme', 'exchange.epistola.app', 'acme', 'Parts'),
                    ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, fingerprint, content_hash, manifest_snapshot)
                SELECT tenant_key, catalog_id, '1.0.0', catalog_key || '-first', '$hash', '$hash', '{}'::jsonb FROM catalogs;
                INSERT INTO catalog_release_dependencies (tenant_key, catalog_id, version, registry, namespace, dependency_key, alias, position, release_version, release_fingerprint)
                SELECT tenant_key, catalog_id, '1.0.0', 'exchange.epistola.app', 'acme', 'parts', 'parts', 0, '1.0.0', '$hash' FROM catalogs WHERE catalog_key = 'letters';
                INSERT INTO catalog_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias, position, dependency_version_id)
                SELECT c.tenant_key, c.catalog_id, c.next_version_id, 'exchange.epistola.app', 'acme', 'parts', 'parts', 0,
                       (SELECT p.next_version_id FROM catalogs p WHERE p.catalog_key = 'parts')
                FROM catalogs c WHERE c.catalog_key = 'letters';
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(
                connection.rows(
                    """
                    SELECT v.state, d.alias, d.release_version, t.state, t.version FROM catalog_version_dependencies d
                    JOIN catalog_versions v ON v.version_id = d.version_id
                    LEFT JOIN catalog_versions t ON t.version_id = d.target_version_id
                    ORDER BY v.state
                    """,
                ),
            ).containsExactly(
                listOf("draft", "parts", null, "draft", null),
                listOf("released", "parts", "1.0.0", "released", "1.0.0"),
            )
            assertThat(
                connection.rows(
                    """
                    SELECT p.catalog_key FROM catalogs l, catalogs p
                    WHERE l.catalog_key = 'letters' AND p.catalog_id = resolve_catalog_alias(l.tenant_key, l.catalog_id, NULL, '1.0.0', 'parts')
                    """,
                ),
            ).`as`("a release's alias, through its pins").containsExactly(listOf("parts"))
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
