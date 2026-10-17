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
 * `core_environment_serves_several_releases` on data shaped like an installation before it: every
 * release an environment served is still served, a second release of the same catalog can now be
 * served beside it, and every logged undeploy names the release it stopped serving in `version`.
 *
 * Seeded with raw SQL on purpose: commands speak only the current schema, and this fixture must
 * match the schema as it was before this migration.
 */
@Tag("integration")
class EnvironmentServesSeveralReleasesMigrationTest {

    @Test
    fun `existing deployments are kept, and an environment can serve a second release of a catalog`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "environment_several_releases_migration"
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
            assertThat(rows(connection, DEPLOYMENTS)).containsExactly(listOf("production", "letters", "1.0.0"))

            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO environment_catalog_deployments (tenant_key, environment_key, catalog_id, version)
                    SELECT 'acme', 'production', catalog_id, '1.0.1' FROM catalogs WHERE tenant_key = 'acme' AND catalog_key = 'letters'
                    """.trimIndent(),
                )
            }
            assertThat(rows(connection, DEPLOYMENTS)).`as`("both releases served")
                .containsExactly(listOf("production", "letters", "1.0.0"), listOf("production", "letters", "1.0.1"))

            assertThat(rows(connection, "SELECT action, version, previous_version FROM environment_deployment_log ORDER BY changed_at"))
                .containsExactly(
                    listOf("DEPLOYED", "0.9.0", null),
                    listOf("DEPLOYED", "1.0.0", "0.9.0"),
                    listOf("UNDEPLOYED", "1.0.0", null),
                    listOf("DEPLOYED", "1.0.0", null),
                )
        }
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type)
                    VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED');
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, manifest_snapshot)
                    SELECT 'acme', catalog_id, v, 'v5:' || repeat('a', 64), '{}'
                    FROM catalogs, (VALUES ('0.9.0'), ('1.0.0'), ('1.0.1')) AS versions(v) WHERE catalog_key = 'letters';
                INSERT INTO environments (id, tenant_key, name) VALUES ('production', 'acme', 'Production');
                INSERT INTO environment_catalog_deployments (tenant_key, environment_key, catalog_id, version)
                    SELECT 'acme', 'production', catalog_id, '1.0.0' FROM catalogs WHERE catalog_key = 'letters';
                INSERT INTO environment_deployment_log (tenant_key, environment_key, catalog_key, registry, namespace, action, version, previous_version, changed_at) VALUES
                    ('acme', 'production', 'letters', 'exchange.epistola.app', 'acme', 'DEPLOYED', '0.9.0', NULL, '2026-10-01T10:00:00Z'),
                    ('acme', 'production', 'letters', 'exchange.epistola.app', 'acme', 'DEPLOYED', '1.0.0', '0.9.0', '2026-10-01T11:00:00Z'),
                    ('acme', 'production', 'letters', 'exchange.epistola.app', 'acme', 'UNDEPLOYED', NULL, '1.0.0', '2026-10-01T12:00:00Z'),
                    ('acme', 'production', 'letters', 'exchange.epistola.app', 'acme', 'DEPLOYED', '1.0.0', NULL, '2026-10-01T13:00:00Z');
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
        /** The last migration before `core_environment_serves_several_releases`. */
        const val BEFORE = "20261017091832"

        const val DEPLOYMENTS = """
            SELECT d.environment_key, c.catalog_key, d.version FROM environment_catalog_deployments d
            JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
            ORDER BY 1, 2, 3
        """
    }
}
