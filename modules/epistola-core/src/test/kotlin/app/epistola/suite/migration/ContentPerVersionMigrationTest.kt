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
 * `core_content_per_version` on a 1.x installation: every content row lands in its catalog's next
 * version, children with their parent, and every reference between resources holds. A catalog can
 * then hold a second draft of the same resources under the same identities, and a resource moved to
 * another catalog takes its children along.
 *
 * Seeded with raw SQL on purpose: commands speak only the current schema, and this fixture must
 * match the schema of 1.x.
 */
@Tag("integration")
class ContentPerVersionMigrationTest {

    @Test
    fun `content keeps its rows and references, and a catalog can then hold a second draft of it`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "content_per_version_migration"
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
                INSERT INTO catalogs (id, tenant_key, name, type) VALUES
                    ('letters', 'acme', 'Letters', 'AUTHORED'),
                    ('shared', 'acme', 'Shared', 'AUTHORED');
                INSERT INTO themes (id, tenant_key, catalog_key, name) VALUES ('brand', 'acme', 'letters', 'Brand');
                UPDATE tenants SET default_theme_resource_id = (SELECT resource_id FROM themes) WHERE id = 'acme';
                INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_resource_id)
                    VALUES ('invoice', 'acme', 'letters', 'Invoice', (SELECT resource_id FROM themes));
                INSERT INTO template_variants (id, tenant_key, template_resource_id, title)
                    SELECT 'default', 'acme', resource_id, 'Default' FROM document_templates;
                INSERT INTO code_lists (slug, tenant_key, catalog_key, display_name, source_type)
                    VALUES ('colours', 'acme', 'letters', 'Colours', 'INLINE');
                INSERT INTO code_list_entries (tenant_key, code_list_resource_id, code, label)
                    SELECT 'acme', resource_id, 'red', 'Red' FROM code_lists;
                INSERT INTO variant_attribute_definitions (id, tenant_key, catalog_key, display_name, code_list_resource_id)
                    VALUES ('colour', 'acme', 'letters', 'Colour', (SELECT resource_id FROM code_lists));
                INSERT INTO stencils (id, tenant_key, catalog_key, name) VALUES ('header', 'acme', 'letters', 'Header');
                """.trimIndent(),
            )
        }
        // Up to the themes table's last schema: what follows moves themes to JSON storage.
        flyway(BEFORE_THEME_STORAGE).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            fun one(sql: String): String? = connection.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
            fun nextOf(catalog: String) = one("SELECT v.version_id FROM catalog_versions v JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE c.catalog_key = '$catalog' AND v.is_next")
            fun count(table: String) = one("SELECT count(*) FROM $table")!!.toInt()
            val next = nextOf("letters")

            // Every row is kept, in the next version, children with their parent, and its references hold.
            CONTENT.forEach { table ->
                assertThat(count(table)).`as`(table).isEqualTo(1)
                assertThat(one("SELECT version_id FROM $table")).`as`("$table version").isEqualTo(next)
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
                INSERT INTO catalog_versions (tenant_key, catalog_id, kind, name)
                SELECT tenant_key, catalog_id, 'draft', 'Redesign' FROM catalogs WHERE catalog_key = 'letters';
                SELECT copy_catalog_version_content('acme', '$next', (SELECT version_id FROM catalog_versions WHERE name = 'Redesign'));
                """.trimIndent(),
            )
            val second = one("SELECT version_id FROM catalog_versions WHERE name = 'Redesign'")
            CONTENT.forEach { table ->
                assertThat(one("SELECT count(*) FROM $table WHERE version_id = '$second'")).`as`("$table copied").isEqualTo("1")
            }
            assertThat(one("SELECT count(*) FROM catalog_resources")).isEqualTo("5")

            // Deleting one version's row keeps an identity the other still holds; discarding a draft
            // takes its content and leaves the other's.
            connection.exec("DELETE FROM themes WHERE version_id = '$second'")
            assertThat(one("SELECT count(*) FROM catalog_resources WHERE resource_type = 'theme'")).isEqualTo("1")
            connection.exec("DELETE FROM catalog_versions WHERE version_id = '$second'")
            CONTENT.forEach { table -> assertThat(count(table)).`as`("$table after discarding the draft").isEqualTo(1) }

            // A template moved to another catalog lands in its version, its variants with it.
            connection.exec(
                "UPDATE document_templates SET catalog_id = (SELECT catalog_id FROM catalogs WHERE catalog_key = 'shared'), version_id = '${nextOf("shared")}' WHERE id = 'invoice'",
            )
            assertThat(one("SELECT version_id FROM template_variants WHERE id = 'default'")).isEqualTo(nextOf("shared"))
            // A content row's version belongs to its catalog.
            assertThatThrownBy { connection.exec("UPDATE themes SET version_id = '${nextOf("shared")}'") }
                .hasMessageContaining("fk_themes_version")
            // A writer names the version.
            assertThatThrownBy { connection.exec("INSERT INTO themes (id, tenant_key, catalog_id, name) SELECT 'other', 'acme', catalog_id, 'Other' FROM catalogs WHERE catalog_key = 'letters'") }
                .hasMessageContaining("version_id")
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private companion object {
        /** The last migration of 1.x (v1.5.0): what an upgrade to 2.0 starts from. */
        const val BEFORE = "20260923150918"

        val CONTENT = listOf("themes", "document_templates", "template_variants", "code_lists", "code_list_entries", "variant_attribute_definitions", "stencils")
    }
}
