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
 * `core_release_settings_on_row` (ADR 0036): a release recorded before its settings were frozen on
 * its row takes them from its manifest: the default theme it names, by identity, and its declared
 * presets. A default theme that is not registered here leaves the row without one.
 *
 * Seeded with raw SQL on purpose: the fixture must match the schema before the migration.
 */
@Tag("integration")
class ReleaseSettingsOnRowMigrationTest {

    @Test
    fun `a migrated release has its manifest's default theme and presets on its row, and none for a theme not registered here`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "release_settings_on_row_migration"
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
        flyway(BEFORE_RELEASE_SETTINGS_ON_ROW).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');
                INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
                SELECT tenant_key, '01a17908-1369-73cc-a8b4-00000000b0a0', 'theme', catalog_id, 'brand' FROM catalogs;
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, fingerprint, content_hash, manifest_snapshot)
                SELECT tenant_key, catalog_id, '1.0.0', 'first', '$hash', '$hash',
                       '{"catalog": {"defaultTheme": {"key": "brand"}, "presets": [{"key": "lead", "name": "Lead"}, {"key": "aside", "name": "Aside", "description": "Beside the text"}]}}'::jsonb
                FROM catalogs;
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, fingerprint, content_hash, manifest_snapshot)
                SELECT tenant_key, catalog_id, '1.1.0', 'second', '$hash', '$hash', '{"catalog": {"defaultTheme": {"key": "gone"}}}'::jsonb FROM catalogs;
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT version, default_theme_resource_id, presets::text FROM catalog_versions WHERE state = 'released' ORDER BY version"))
                .containsExactly(
                    listOf("1.0.0", "01a17908-1369-73cc-a8b4-00000000b0a0", """[{"key": "aside", "name": "Aside", "description": "Beside the text"}, {"key": "lead", "name": "Lead", "description": null}]"""),
                    listOf("1.1.0", null, "[]"),
                )
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
