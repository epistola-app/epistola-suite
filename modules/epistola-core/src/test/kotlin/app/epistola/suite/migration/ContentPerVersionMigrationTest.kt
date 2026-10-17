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
 * `core_content_per_version` puts every content row in its catalog's content version: existing rows
 * are backfilled, children take their parent's, a writer that names none gets the catalog's only one,
 * and a resource moved to another catalog follows into that catalog's version.
 */
@Tag("integration")
class ContentPerVersionMigrationTest {

    @Test
    fun `content rows belong to their catalog's version, and keep doing so as they are written and moved`() {
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
            // Rows as the schema before this migration holds them; no command of today writes that schema.
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type) VALUES
                    ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED'),
                    ('shared', 'acme', 'exchange.epistola.app', 'acme', 'Shared', 'AUTHORED');
                INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind)
                SELECT tenant_key, catalog_id, '1.0.0', 'draft' FROM catalogs;
                INSERT INTO document_templates (id, tenant_key, catalog_id, name)
                    VALUES ('invoice', 'acme', (SELECT catalog_id FROM catalogs WHERE catalog_key = 'letters'), 'Invoice');
                INSERT INTO template_variants (id, tenant_key, template_resource_id, title, template_model)
                    VALUES ('default', 'acme', (SELECT resource_id FROM document_templates), 'Default', '{}');
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            fun one(sql: String): String? = connection.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
            fun versionOf(catalog: String) = one("SELECT v.version_id FROM catalog_versions v JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE c.catalog_key = '$catalog'")

            assertThat(one("SELECT version_id FROM document_templates WHERE id = 'invoice'")).`as`("backfilled").isEqualTo(versionOf("letters"))
            assertThat(one("SELECT version_id FROM template_variants WHERE id = 'default'")).`as`("a child takes its parent's").isEqualTo(versionOf("letters"))

            connection.exec("INSERT INTO themes (id, tenant_key, catalog_id, name) VALUES ('brand', 'acme', (SELECT catalog_id FROM catalogs WHERE catalog_key = 'shared'), 'Brand')")
            assertThat(one("SELECT version_id FROM themes WHERE id = 'brand'")).`as`("a writer that names none").isEqualTo(versionOf("shared"))

            connection.exec("UPDATE document_templates SET catalog_id = (SELECT catalog_id FROM catalogs WHERE catalog_key = 'shared') WHERE id = 'invoice'")
            assertThat(one("SELECT version_id FROM document_templates WHERE id = 'invoice'")).`as`("a move follows").isEqualTo(versionOf("shared"))
            assertThat(one("SELECT version_id FROM template_variants WHERE id = 'default'")).`as`("its variants with it").isEqualTo(versionOf("shared"))

            // Deleting a version deletes the content it holds.
            connection.exec("DELETE FROM catalog_versions WHERE catalog_id = (SELECT catalog_id FROM catalogs WHERE catalog_key = 'shared')")
            assertThat(one("SELECT count(*) FROM document_templates")).isEqualTo("0")

            // A catalog with two content versions makes a writer name one.
            connection.exec(
                """
                DROP INDEX uq_catalog_versions_one_per_catalog;
                INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind)
                SELECT tenant_key, catalog_id, v, 'draft' FROM catalogs, unnest(ARRAY['1.1.0', '2.0.0']) v WHERE catalog_key = 'shared';
                """.trimIndent(),
            )
            assertThatThrownBy { connection.exec("INSERT INTO themes (id, tenant_key, catalog_id, name) VALUES ('other', 'acme', (SELECT catalog_id FROM catalogs WHERE catalog_key = 'shared'), 'Other')") }
                .hasMessageContaining("has 2 content versions; name the version")
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private companion object {
        /** The last migration before the one under test. */
        const val BEFORE = "20261017102724"
    }
}
