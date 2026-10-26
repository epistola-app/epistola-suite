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
 * `core_deployments_by_version_row` (ADR 0036, step (d)): a deployment names one version row. A
 * release deployed by its number names the release's row, a deployed draft keeps naming the draft's
 * row, and the number and draft columns are gone.
 *
 * Seeded with raw SQL on purpose: the fixture must match the schema before the migration.
 */
@Tag("integration")
class DeploymentsByVersionRowMigrationTest {

    @Test
    fun `a deployment names the version row it serves, a release's and a draft's alike`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "deployments_by_version_row_migration"
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
        flyway(BEFORE_DEPLOYMENTS_BY_VERSION_ROW).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO environments (id, tenant_key, name) VALUES ('test', 'acme', 'Test'), ('prod', 'acme', 'Production');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, fingerprint, content_hash, manifest_snapshot)
                SELECT tenant_key, catalog_id, '1.0.0', 'letters-first', '$hash', '$hash', '{}'::jsonb FROM catalogs;
                INSERT INTO environment_catalog_deployments (tenant_key, environment_key, catalog_id, version)
                SELECT tenant_key, 'prod', catalog_id, '1.0.0' FROM catalogs;
                INSERT INTO environment_catalog_deployments (tenant_key, environment_key, catalog_id, draft_id)
                SELECT tenant_key, 'test', catalog_id, next_version_id FROM catalogs;
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(
                connection.rows(
                    """
                    SELECT d.environment_key, v.state, v.version FROM environment_catalog_deployments d
                    JOIN catalog_versions v ON v.tenant_key = d.tenant_key AND v.version_id = d.target_version_id
                    ORDER BY d.environment_key
                    """,
                ),
            ).containsExactly(
                listOf("prod", "released", "1.0.0"),
                listOf("test", "draft", null),
            )
            assertThat(
                connection.rows(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'environment_catalog_deployments' AND column_name IN ('version', 'draft_id')
                    """,
                ),
            ).`as`("the number and draft columns are gone").isEmpty()
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
