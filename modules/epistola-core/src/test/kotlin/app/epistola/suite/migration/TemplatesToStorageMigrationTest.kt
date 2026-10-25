// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import db.migration.core.V20261025170700__core_templates_to_storage
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager

/**
 * `core_templates_to_storage` and `core_drop_templates` (ADR 0033, step 7b): a template and its
 * variants become one content in its released form, each variant's model a content of its own; a
 * data example is kept by its key, derived when it had none; a variant's description beside the
 * resource; a ready mark still holds; findings follow a variant through its template's content.
 *
 * Seeded with raw SQL on purpose: the tables are the 1.x shape no command writes any more.
 */
@Tag("integration")
class TemplatesToStorageMigrationTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `a data example's key is derived from its name as a release derives it`() {
        val key = V20261025170700__core_templates_to_storage.Companion::dataExampleKey
        // Golden vectors: `CatalogKeys.deriveDataExampleKey` of contract 2.0.0-alpha.1.
        assertThat(key("Ärger & Co.", emptySet())).isEqualTo("arger-co")
        assertThat(key("Standaard brief", setOf("standaard-brief"))).isEqualTo("standaard-brief-2")
        assertThat(key("Standaard brief", setOf("standaard-brief", "standaard-brief-2"))).isEqualTo("standaard-brief-3")
        assertThat(key("A", emptySet())).isEqualTo("a00")
        assertThat(key("!!!", emptySet())).isEqualTo("example")
        assertThat(key("x".repeat(60), emptySet())).isEqualTo("x".repeat(50))
        assertThat(key("x".repeat(60), setOf("x".repeat(50)))).isEqualTo("x".repeat(48) + "-2")
        assertThat(key("Brief — Nederlands (2024)", emptySet())).isEqualTo("brief-nederlands-2024")
    }

    @Test
    fun `templates become their content, examples keep keys, descriptions and marks stay`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "templates_to_storage_migration"
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
        val model = """{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":["children"],"styles":null,"stylePreset":null,"props":null},""" +
            """"s1":{"id":"s1","type":"stencil","slots":["s1:children"],"styles":null,"stylePreset":null,"props":{"stencilKey":"header","catalogKey":"letters","namespace":"acme"}}},""" +
            """"slots":{"children":{"id":"children","nodeId":"root","name":"children","children":["s1"]},"s1:children":{"id":"s1:children","nodeId":"s1","name":"children","children":[]}},""" +
            """"themeRef":{"type":"override","themeKey":"brand","catalogKey":"letters"},"pageSettingsOverride":null,"documentStylesOverride":{"fontFamily":{"key":"serif","catalogKey":"system"}}}"""

        flyway(BEFORE_TEMPLATE_STORAGE).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');
                -- The theme a template names by identity; its identity is all this needs.
                INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, resource_key, catalog_id)
                SELECT 'acme', '00000000-0000-0000-0000-0000000000aa', 'theme', 'brand', catalog_id FROM catalogs WHERE catalog_key = 'letters';

                INSERT INTO document_templates (id, tenant_key, name, description, catalog_id, version_id, theme_resource_id, pdfa_enabled, data_model, data_examples, revision)
                SELECT 'invoice', c.tenant_key, 'Invoice', 'Bills', c.catalog_id, v.version_id, '00000000-0000-0000-0000-0000000000aa', FALSE,
                       '{"type":"object"}'::jsonb,
                       '[{"id":"u1","name":"Standaard brief","data":{"a":1}},{"id":"u2","name":"Standaard brief","data":{"a":2}},{"id":"u3","name":"Kept","data":{},"key":"kept-key"}]'::jsonb, 7
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';
                INSERT INTO template_variants (id, tenant_key, version_id, template_resource_id, title, description, attributes, is_default, template_model, metadata, created_at)
                SELECT x.id, t.tenant_key, t.version_id, t.resource_id, x.title, x.description, x.attributes::jsonb, x.is_default, '$model'::jsonb, x.metadata::jsonb, x.created_at
                FROM document_templates t,
                     (VALUES ('second', 'Second', 'For courts', '{"system.locale":"nl"}', FALSE, '{"app.epistola.suite":{"a":1}}', TIMESTAMPTZ '2026-01-02 00:00:00+00'),
                             ('default', 'Default', NULL, '{}', TRUE, NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00')) AS x(id, title, description, attributes, is_default, metadata, created_at);

                -- Both variants marked ready; a finding about the second.
                INSERT INTO resource_ready_marks (tenant_key, resource_id, part, digest, version_id)
                SELECT tenant_key, resource_id, part, digest, version_id FROM resource_content_digests d
                WHERE tenant_key = 'acme' AND resource_id = (SELECT resource_id FROM document_templates);
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            val payload = mapper.readTree(connection.rows("SELECT payload::text FROM current_templates WHERE id = 'invoice'").single().single())
            val resource = payload.path("resource")
            assertThat(listOf(resource.path("name").asString(), resource.path("description").asString(), resource.path("themeKey").asString(), resource.path("pdfaEnabled").asBoolean()))
                .containsExactly("Invoice", "Bills", "brand", false)
            assertThat(resource.path("themeCatalogKey").isNull).`as`("a theme of its own catalog is named relatively").isTrue()
            assertThat(resource.path("dataExamples").toList().map { it.path("key").asString() }).containsExactly("standaard-brief", "standaard-brief-2", "kept-key")
            assertThat(resource.path("dataExamples").toList().none { it.has("id") }).`as`("no example keeps the id").isTrue()
            assertThat(resource.path("variants").toList().map { it.path("key").asString() }).`as`("the default first").containsExactly("default", "second")
            assertThat(resource.has("templateModel") || resource.path("variants").any { it.has("templateModel") || it.has("title") })
                .`as`("the template's content holds no variant's own fields").isFalse()
            // Each variant a row of its own, with its title, description, metadata and model.
            val second = connection.rows("SELECT title, description, metadata::text FROM current_template_variants WHERE id = 'second'").single()
            assertThat(second.take(2)).containsExactly("Second", "For courts")
            assertThat(mapper.readTree(second[2]).path("app.epistola.suite").path("a").asInt()).isEqualTo(1)
            assertThat(connection.rows("SELECT resource_key FROM release_resources WHERE resource_type = 'variant' ORDER BY 1").map { it.single() })
                .containsExactly("default", "second")

            val stored = mapper.readTree(connection.rows("SELECT template_model::text FROM current_template_variants WHERE id = 'second'").single().single())
            assertThat(stored.path("themeRef").path("catalogKey").isNull).`as`("its own theme, relative").isTrue()
            assertThat(stored.path("nodes").path("s1").path("props").has("catalogKey")).`as`("its own stencil, relative").isFalse()
            assertThat(stored.path("nodes").path("s1").path("props").has("namespace")).`as`("no name qualifier").isFalse()
            assertThat(stored.path("documentStylesOverride").path("fontFamily").path("catalogKey").asString()).`as`("another catalog's font, by alias").isEqualTo("system")
            assertThat(connection.rows("SELECT resource_type || '/' || COALESCE(alias, '') || '/' || resource_key FROM resource_references ORDER BY 1").map { it.single() })
                // The template's content names its theme; each variant's (one model, two titles) its own.
                .containsExactlyInAnyOrder("font/system/serif", "font/system/serif", "stencil//header", "stencil//header", "theme//brand", "theme//brand", "theme//brand")
            assertThat(connection.rows("SELECT revision FROM current_templates").single().single()).`as`("its revision stays").isEqualTo("7")
            assertThat(connection.rows("SELECT count(*) FROM resource_ready_marks m JOIN resource_content_digests d USING (tenant_key, version_id, resource_id, part) WHERE d.digest = m.digest").single().single())
                .`as`("both marks hold against the content").isEqualTo("2")
            assertThat(connection.rows("SELECT CAST(to_regclass('document_templates') IS NULL AND to_regclass('template_variants') IS NULL AS TEXT)").single().single()).isEqualTo("true")
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
