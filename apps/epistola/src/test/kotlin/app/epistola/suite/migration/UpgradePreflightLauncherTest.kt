// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * The migration step on a 1.x database whose upgrade could not finish: it refuses before a single
 * migration runs, so the database stays as 1.x left it and 1.x keeps working; once fixed, the same
 * step migrates. Runs [MigrationLauncher.runMigration], the production entry point.
 */
@Tag("integration")
class UpgradePreflightLauncherTest {

    private val postgres = TestRuntimeLifecycle.postgres()

    @Test
    fun `catalogs that use each other stop the migration before it starts, and once fixed it runs`() {
        val url = withCycle("preflight_launcher")

        assertThat(MigrationLauncher.runMigration(args(url))).isEqualTo(1)
        assertThat(applied(url)).`as`("not one migration ran").isEqualTo(LAST_1X)

        // Fixed in 1.x: the stencil no longer uses letters.
        sql(url, """UPDATE stencil_versions SET content = '{"nodes": {}}'""")
        assertThat(MigrationLauncher.runMigration(args(url))).isEqualTo(0)
        assertThat(applied(url)).isGreaterThan(LAST_1X)
    }

    @Test
    fun `an operator skips a check by id`() {
        val url = withCycle("preflight_launcher_skip")

        val code = MigrationLauncher.runMigration(args(url) + "--epistola.migration.preflight.skip=1x-catalog-dependency-cycles")

        assertThat(code).isEqualTo(0)
        assertThat(applied(url)).isGreaterThan(LAST_1X)
    }

    /** A 1.x database whose catalogs letters and brand use each other. */
    private fun withCycle(name: String): String {
        val url = oneXDatabase(name)
        sql(
            url,
            """
            INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
            INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', 'acme', 'Letters', 'AUTHORED'), ('brand', 'acme', 'Brand', 'AUTHORED');
            INSERT INTO themes (id, tenant_key, catalog_key, name) VALUES ('house', 'acme', 'brand', 'House');
            INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_resource_id)
                SELECT 'invoice', 'acme', 'letters', 'Invoice', resource_id FROM themes WHERE id = 'house';
            INSERT INTO stencils (id, tenant_key, catalog_key, name) VALUES ('footer', 'acme', 'brand', 'Footer');
            INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, status, published_at)
                SELECT 1, 'acme', resource_id, '{"nodes": {"logo": {"type": "image", "props": {"catalogKey": "letters"}}}}', 'published', NOW() FROM stencils;
            """,
        )
        return url
    }

    private fun args(url: String) = arrayOf(
        "--spring.datasource.url=$url",
        "--spring.datasource.username=${postgres.username}",
        "--spring.datasource.password=${postgres.password}",
    )

    private fun oneXDatabase(name: String): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$name\"")
                it.execute("CREATE DATABASE \"$name\"")
            }
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$name$1")
        Flyway.configure().dataSource(url, postgres.username, postgres.password).locations("classpath:db/migration").target(LAST_1X).load().migrate()
        return url
    }

    private fun sql(url: String, statements: String) = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
        connection.createStatement().use { it.execute(statements.trimIndent()) }
    }

    private fun applied(url: String): String = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
        connection.createStatement().use { st ->
            st.executeQuery("SELECT max(version) FROM flyway_schema_history WHERE success").use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private companion object {
        /** The last migration 1.x shipped (1.5.0). */
        const val LAST_1X = "20260923150918"
    }
}
