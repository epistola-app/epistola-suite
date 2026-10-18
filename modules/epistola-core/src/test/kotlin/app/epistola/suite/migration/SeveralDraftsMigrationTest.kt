// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `core_several_drafts` keys content per catalog version and lets a catalog have several drafts,
 * keeping every row and every reference: content rows stay in their version, references between
 * resources now name identities, and a second draft can hold a copy of the same resources.
 */
@Tag("integration")
class SeveralDraftsMigrationTest {

    @Test
    fun `content keeps its rows and references, and a catalog can then hold a second draft of it`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "several_drafts_migration"
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
            // Rows as the schema before this migration holds them, its transitional triggers filling
            // version_id; no command of today writes that schema.
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type) VALUES
                    ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED');
                INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind)
                SELECT tenant_key, catalog_id, '1.0.0', 'draft' FROM catalogs;
                INSERT INTO themes (id, tenant_key, catalog_id, name) SELECT 'brand', 'acme', catalog_id, 'Brand' FROM catalogs;
                UPDATE tenants SET default_theme_resource_id = (SELECT resource_id FROM themes) WHERE id = 'acme';
                INSERT INTO document_templates (id, tenant_key, catalog_id, name, theme_resource_id)
                SELECT 'invoice', 'acme', catalog_id, 'Invoice', (SELECT resource_id FROM themes) FROM catalogs;
                INSERT INTO template_variants (id, tenant_key, template_resource_id, title, template_model)
                SELECT 'default', 'acme', resource_id, 'Default', '{}' FROM document_templates;
                INSERT INTO code_lists (slug, tenant_key, catalog_id, display_name, source_type)
                SELECT 'colours', 'acme', catalog_id, 'Colours', 'INLINE' FROM catalogs;
                INSERT INTO code_list_entries (tenant_key, code_list_resource_id, code, label)
                SELECT 'acme', resource_id, 'red', 'Red' FROM code_lists;
                INSERT INTO variant_attribute_definitions (id, tenant_key, catalog_id, display_name, code_list_resource_id)
                SELECT 'colour', 'acme', catalog_id, 'Colour', (SELECT resource_id FROM code_lists) FROM catalogs;
                INSERT INTO stencils (id, tenant_key, catalog_id, name, content) SELECT 'header', 'acme', catalog_id, 'Header', '{}' FROM catalogs;
                INSERT INTO resource_ready_marks (tenant_key, resource_id, part, digest)
                SELECT 'acme', resource_id, '', repeat('0', 64) FROM themes;
                INSERT INTO catalog_dependencies (tenant_key, catalog_id, registry, namespace, dependency_key, version_selector, position)
                SELECT 'acme', catalog_id, 'exchange.epistola.app', 'acme', 'shared', '^1.0.0', 0 FROM catalogs;
                """.trimIndent(),
            )
        }
        flyway(UNDER_TEST).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            fun one(sql: String): String? = connection.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
            val draft = one("SELECT version_id FROM catalog_versions WHERE version = '1.0.0'")
            fun count(table: String) = one("SELECT count(*) FROM $table")!!.toInt()

            // Every row is kept, in its version, and its references hold.
            CONTENT.forEach { table ->
                assertThat(count(table)).`as`(table).isEqualTo(1)
                assertThat(one("SELECT version_id FROM $table")).`as`("$table version").isEqualTo(draft)
            }
            assertThat(one("SELECT theme_resource_id FROM document_templates")).isEqualTo(one("SELECT resource_id FROM themes"))
            assertThat(one("SELECT default_theme_resource_id FROM tenants")).isEqualTo(one("SELECT resource_id FROM themes"))
            assertThat(one("SELECT code_list_resource_id FROM variant_attribute_definitions")).isEqualTo(one("SELECT resource_id FROM code_lists"))
            assertThat(one("SELECT count(*) FROM catalog_resources")).isEqualTo("5")
            assertThat(one("SELECT string_agg(a.attname, ',' ORDER BY k.n) FROM pg_constraint c CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY k(attnum, n) JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum WHERE c.conname = 'themes_pkey'"))
                .isEqualTo("tenant_key,version_id,resource_id")

            // A second draft, filled with a copy, holds the same resources under the same identities.
            connection.exec(
                """
                INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind, base_version)
                SELECT tenant_key, catalog_id, '2.0.0', 'draft', NULL FROM catalogs;
                SELECT copy_catalog_version_content('acme', (SELECT version_id FROM catalog_versions WHERE version = '1.0.0'),
                                                    (SELECT version_id FROM catalog_versions WHERE version = '2.0.0'));
                """.trimIndent(),
            )
            val second = one("SELECT version_id FROM catalog_versions WHERE version = '2.0.0'")
            (CONTENT + "resource_ready_marks" + "catalog_dependencies").forEach { table ->
                assertThat(one("SELECT count(*) FROM $table WHERE version_id = '$second'")).`as`("$table copied").isEqualTo("1")
            }
            assertThat(one("SELECT count(*) FROM catalog_resources")).isEqualTo("5")

            // The current version is the highest draft, unless the transaction targets another.
            assertThat(one("SELECT version_id FROM catalog_current_versions")).isEqualTo(second)
            connection.autoCommit = false
            connection.exec("SELECT set_config('epistola.content_version', '$draft', TRUE)")
            assertThat(one("SELECT version_id FROM catalog_current_versions")).isEqualTo(draft)
            connection.commit()
            connection.autoCommit = true
            assertThat(one("SELECT version_id FROM catalog_current_versions")).isEqualTo(second)

            // Deleting one version's row keeps an identity the other still holds; the last one takes it.
            connection.exec("DELETE FROM themes WHERE version_id = '$second'")
            assertThat(one("SELECT count(*) FROM catalog_resources WHERE resource_type = 'theme'")).isEqualTo("1")
            connection.exec("DELETE FROM catalog_versions WHERE version = '2.0.0'")
            CONTENT.forEach { table -> assertThat(count(table)).`as`("$table after discarding 2.0.0").isEqualTo(1) }

            // No writer may leave the version out any more: the trigger that guessed it is gone.
            assertThatThrownBy { connection.exec("INSERT INTO themes (id, tenant_key, catalog_id, name) SELECT 'other', 'acme', catalog_id, 'Other' FROM catalogs") }
                .hasMessageContaining("version_id")
            // A subscribed catalog still has one installed row.
            assertThatThrownBy {
                connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind) SELECT tenant_key, catalog_id, v, 'installed' FROM catalogs, unnest(ARRAY['1.0.0-i', '2.0.0-i']) v")
            }.hasMessageContaining("uq_catalog_versions_one_installed")
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private companion object {
        /** The migration under test: later ones change what it left, and are tested on their own. */
        const val UNDER_TEST = "20261017115602"

        /** The last migration before the one under test. */
        const val BEFORE = "20261017112401"

        val CONTENT = listOf("themes", "document_templates", "template_variants", "code_lists", "code_list_entries", "variant_attribute_definitions", "stencils")
    }
}
