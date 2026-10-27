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
 * `core_upgrade_to_2_0` on a 1.x installation's versions: each variant's, data contract's and
 * stencil's latest published content is kept for the first release (`catalog_upgrade_published`), the
 * history is summarised in `app_metadata` (`upgrade-1x-history`), and the version tables are dropped.
 * (The load-test module's reference to them is dropped by its own migration; this module's tests do
 * not run it.)
 *
 * Seeded with raw SQL on purpose: commands speak only the current schema, and this fixture must
 * match the schema of 1.x.
 */
@Tag("integration")
class OneXVersionHistoryMigrationTest {

    @Test
    fun `the latest published content is kept for the first release, the rest summarised, and the tables dropped`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "one_x_version_history_migration"
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
                INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', 'acme', 'Letters', 'AUTHORED');
                INSERT INTO environments (id, tenant_key, name) VALUES ('production', 'acme', 'Production');
                INSERT INTO document_templates (id, tenant_key, catalog_key, name) VALUES ('invoice', 'acme', 'letters', 'Invoice');
                INSERT INTO template_variants (id, tenant_key, template_resource_id, title, is_default)
                    SELECT 'main', 'acme', resource_id, 'Main', TRUE FROM document_templates;
                INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at, archived_at)
                    SELECT v, 'acme', resource_id, 'main', jsonb_build_object('rootNodeId', 'root-1', 'nodes', jsonb_build_object('root-1', jsonb_build_object('type', 'page')), 'marker', 'v' || v),
                           s, CASE WHEN s = 'draft' THEN NULL ELSE NOW() END, CASE WHEN s = 'archived' THEN NOW() END
                    FROM document_templates, (VALUES (1, 'archived'), (2, 'published'), (3, 'draft')) AS versions(v, s);
                INSERT INTO contract_versions (id, tenant_key, template_resource_id, data_model, status, published_at)
                    SELECT v, 'acme', resource_id, jsonb_build_object('marker', 'contract ' || v), s, CASE WHEN s = 'draft' THEN NULL ELSE NOW() END
                    FROM document_templates, (VALUES (1, 'published'), (2, 'draft')) AS versions(v, s);
                INSERT INTO stencils (id, tenant_key, catalog_key, name) VALUES ('header', 'acme', 'letters', 'Header');
                INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, status, published_at)
                    SELECT 1, 'acme', resource_id, '{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}}, "slots": {}}', 'published', NOW() FROM stencils;
                INSERT INTO environment_activations (tenant_key, environment_key, variant_key, version_key, template_resource_id)
                    SELECT 'acme', 'production', 'main', 2, resource_id FROM document_templates;
                """.trimIndent(),
            )
        }
        // To the upgrade migration: what it keeps of what was published, until V20261027112414 drafts it and drops it.
        flyway(UPGRADE).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT kind, part, payload->'templateModel'->>'marker', payload->'dataModel'->>'marker' FROM catalog_upgrade_published ORDER BY kind"))
                .`as`("the latest published of each, not an archived one or a draft")
                .containsExactly(
                    listOf("contract", "", null, "contract 1"),
                    listOf("stencil", "", null, null),
                    listOf("variant", "main", "v2", null),
                )

            fun history(list: String) = connection.rows(
                "SELECT v->>'version', v->>'status' FROM app_metadata, jsonb_array_elements(value->'$list') v WHERE key = 'upgrade-1x-history' ORDER BY 1",
            )
            assertThat(history("templateVersions")).containsExactly(listOf("1", "archived"), listOf("2", "published"), listOf("3", "draft"))
            assertThat(history("contractVersions")).containsExactly(listOf("1", "published"), listOf("2", "draft"))
            assertThat(history("stencilVersions")).containsExactly(listOf("1", "published"))
            assertThat(
                connection.rows(
                    "SELECT v->>'environment', v->>'catalog', v->>'template', v->>'variant', v->>'version' FROM app_metadata, jsonb_array_elements(value->'activations') v WHERE key = 'upgrade-1x-history'",
                ),
            ).containsExactly(listOf("production", "letters", "invoice", "main", "2"))

            assertThat(connection.rows("SELECT table_name FROM information_schema.tables WHERE table_name IN ('template_versions', 'contract_versions', 'stencil_versions', 'environment_activations')"))
                .isEmpty()
        }
    }

    @Test
    fun `an installation without 1_x versions gets no summary`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "one_x_version_history_none"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$database\"")
                it.execute("CREATE DATABASE \"$database\"")
            }
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        Flyway.configure().dataSource(url, postgres.username, postgres.password).locations("classpath:db/migration").load().migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT key FROM app_metadata WHERE key = 'upgrade-1x-history'")).isEmpty()
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map { rs.getString(it) }) }
        }
    }

    private companion object {
        /** The last migration of 1.x (v1.5.0): what an upgrade to 2.0 starts from. */
        const val BEFORE = "20260923150918"

        /** The 2.0 upgrade migration (`core_upgrade_to_2_0`). */
        const val UPGRADE = "20261018201400"
    }
}
