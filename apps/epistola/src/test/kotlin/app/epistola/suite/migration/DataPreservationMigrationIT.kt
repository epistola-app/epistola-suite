package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards the RC1 promise: **the database is stable and user data survives every
 * migration**. Seeds a representative tenant on the 1.0.0-RC1 baseline schema, then
 * applies every newer migration and asserts the user data is byte-identical.
 *
 * It also guards the flip side — that an **intentional, scoped data migration does exactly
 * what it claims**: retiring the `stencil-parameters` feature toggle (issue #668) must delete
 * that key's orphaned `feature_toggles` rows while leaving unrelated toggle rows untouched.
 *
 * Mechanics: a fresh logical database in the shared Testcontainer is migrated with
 * `spring.flyway.target` pinned to the last RC1 migration, seeded with raw SQL (raw on
 * purpose — commands can only speak the *current* schema, while this fixture must match
 * the frozen RC1 shape, which never changes because released migrations are immutable),
 * then migrated to latest via the exact production migration context. Every migration that
 * lands after RC1 is thereby exercised against RC1-shaped data and fails this test if it
 * drops or mangles a preserved row — or fails to perform (or over-performs) an expected cleanup.
 */
@Tag("integration")
class DataPreservationMigrationIT {

    @Test
    fun `data seeded on the RC1 schema survives migration to the latest schema`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val databaseName = "data_preservation_it"
        adminConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$databaseName\"")
                it.execute("CREATE DATABASE \"$databaseName\"")
            }
        }
        val targetUrl = perDatabaseUrl(postgres.jdbcUrl, postgres.host, postgres.firstMappedPort, databaseName)

        // 1. Migrate the fresh database to the RC1 baseline only.
        runMigration(targetUrl, postgres.username, postgres.password, "spring.flyway.target=$RC1_LAST_MIGRATION")

        // 2. Seed a representative tenant on the RC1-shaped schema.
        connect(targetUrl, postgres.username, postgres.password).use { seed(it) }

        // 3. Apply everything newer than RC1 (the production migration path).
        runMigration(targetUrl, postgres.username, postgres.password)

        // 4. The seeded data must be intact.
        connect(targetUrl, postgres.username, postgres.password).use { verify(it) }
    }

    private fun runMigration(url: String, username: String, password: String, vararg props: String) {
        MigrationLauncher.migrationApplication()
            .run(
                "--spring.datasource.url=$url",
                "--spring.datasource.username=$username",
                "--spring.datasource.password=$password",
                "--epistola.migration.mode=migrate",
                "--spring.flyway.clean-disabled=true",
                *props.map { "--$it" }.toTypedArray(),
            )
            .close()
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO tenants (id, name, default_locale)
                VALUES ('$TENANT', 'Preservation Tenant', 'nl-NL');

                INSERT INTO catalogs (id, tenant_key, name, type)
                VALUES ('default', '$TENANT', 'Default Catalog', 'AUTHORED');

                INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles)
                VALUES ('brand', '$TENANT', 'default', 'Brand Theme', '$THEME_STYLES'::jsonb);

                INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_catalog_key, theme_key)
                VALUES ('invoice', '$TENANT', 'default', 'Invoice Template', 'default', 'brand');

                INSERT INTO template_variants (id, tenant_key, catalog_key, template_key, title, is_default)
                VALUES ('main', '$TENANT', 'default', 'invoice', 'Main Variant', true);

                INSERT INTO template_versions (id, tenant_key, catalog_key, template_key, variant_key,
                                               template_model, status, published_at, referenced_paths)
                VALUES (1, '$TENANT', 'default', 'invoice', 'main',
                        '$TEMPLATE_MODEL'::jsonb, 'published', NOW(), '["customer.name","total"]'::jsonb);

                -- An orphaned toggle row for the retired 'stencil-parameters' feature (deleted by
                -- V20260708110402) plus a control row for a still-live feature that must survive.
                INSERT INTO feature_toggles (tenant_key, feature_key, enabled)
                VALUES ('$TENANT', 'stencil-parameters', true),
                       ('$TENANT', 'support-feedback', false);
                """.trimIndent(),
            )
        }
    }

    private fun verify(connection: Connection) {
        connection.createStatement().use { statement ->
            fun one(sql: String): String? = statement.executeQuery(sql).use { rs ->
                assertThat(rs.next()).describedAs("row missing after migration: $sql").isTrue()
                rs.getString(1)
            }

            assertThat(one("SELECT name FROM tenants WHERE id = '$TENANT'"))
                .isEqualTo("Preservation Tenant")
            assertThat(one("SELECT default_locale FROM tenants WHERE id = '$TENANT'"))
                .isEqualTo("nl-NL")
            assertThat(one("SELECT type FROM catalogs WHERE tenant_key = '$TENANT' AND id = 'default'"))
                .isEqualTo("AUTHORED")
            assertThat(one("SELECT document_styles::text FROM themes WHERE tenant_key = '$TENANT' AND id = 'brand'"))
                .isEqualTo(one("SELECT '$THEME_STYLES'::jsonb::text"))
            assertThat(one("SELECT theme_key FROM document_templates WHERE tenant_key = '$TENANT' AND id = 'invoice'"))
                .isEqualTo("brand")
            assertThat(one("SELECT title FROM template_variants WHERE tenant_key = '$TENANT' AND template_key = 'invoice' AND id = 'main'"))
                .isEqualTo("Main Variant")
            assertThat(one("SELECT template_model::text FROM template_versions WHERE tenant_key = '$TENANT' AND template_key = 'invoice' AND variant_key = 'main' AND id = 1"))
                .isEqualTo(one("SELECT '$TEMPLATE_MODEL'::jsonb::text"))
            assertThat(one("SELECT status FROM template_versions WHERE tenant_key = '$TENANT' AND template_key = 'invoice' AND variant_key = 'main' AND id = 1"))
                .isEqualTo("published")
            assertThat(one("SELECT referenced_paths::text FROM template_versions WHERE tenant_key = '$TENANT' AND template_key = 'invoice' AND variant_key = 'main' AND id = 1"))
                .isEqualTo(one("""SELECT '["customer.name","total"]'::jsonb::text"""))

            // Intentional scoped cleanup (V20260708110402, issue #668): the retired
            // stencil-parameters toggle's orphaned rows are gone, unrelated toggles survive.
            assertThat(one("SELECT count(*) FROM feature_toggles WHERE tenant_key = '$TENANT' AND feature_key = 'stencil-parameters'"))
                .describedAs("retired stencil-parameters toggle rows must be deleted")
                .isEqualTo("0")
            assertThat(one("SELECT count(*) FROM feature_toggles WHERE tenant_key = '$TENANT' AND feature_key = 'support-feedback'"))
                .describedAs("unrelated feature-toggle rows must survive the scoped delete")
                .isEqualTo("1")
        }
    }

    private fun adminConnection(url: String, username: String, password: String): Connection = DriverManager.getConnection(url, username, password)

    private fun connect(url: String, username: String, password: String): Connection = DriverManager.getConnection(url, username, password)

    private fun perDatabaseUrl(containerUrl: String, host: String, port: Int, databaseName: String): String {
        val queryParams = containerUrl.substringAfter('?', "")
        val base = "jdbc:postgresql://$host:$port/$databaseName"
        return if (queryParams.isEmpty()) base else "$base?$queryParams"
    }

    companion object {
        /**
         * The last migration shipped in 1.0.0-RC1 — the frozen baseline this fixture's
         * SQL is written against. Do NOT bump this when adding migrations; the whole
         * point is that data seeded on this schema must survive everything after it.
         */
        private const val RC1_LAST_MIGRATION = "20260622102813"

        private const val TENANT = "preserve-tenant"
        private const val THEME_STYLES = """{"fontFamily": "serif", "fontSize": 11}"""
        private const val TEMPLATE_MODEL =
            """{"rootNodeId": "root-1", "nodes": {"root-1": {"type": "page"}}, "marker": "rc1-preservation"}"""
    }
}
