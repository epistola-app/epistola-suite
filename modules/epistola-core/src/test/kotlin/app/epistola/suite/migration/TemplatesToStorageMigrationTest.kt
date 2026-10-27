// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import db.migration.core.frozen.DataExampleKeys
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 2.0 upgrade's templates (steps 8 to 10) on a 1.x template: its content names its theme and each
 * variant by key, attributes and default, the default first; each variant is a row of its own with
 * its title, description and working model; a data example is kept by its key, derived when it had
 * none; a variant whose draft differs from what it published is not ready, and the "Published in 1.x"
 * draft holds what it published, ready; a variant attribute's qualifier declares its catalog.
 *
 * Seeded with raw SQL on purpose: the tables are the 1.x shape no command writes any more.
 */
@Tag("integration")
class TemplatesToStorageMigrationTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `a data example's key is derived from its name as a release derives it`() {
        val key = DataExampleKeys::dataExampleKey
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
    fun `a 1_x template becomes its content and its variants' rows, in its next draft and what it published`() {
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
        fun model(text: String) = """{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":["children"]},""" +
            """"t1":{"id":"t1","type":"text","props":{"content":"$text"}}},""" +
            """"slots":{"children":{"id":"children","nodeId":"root","name":"children","children":["t1"]}},""" +
            """"themeRef":{"type":"override","themeId":"brand"}}"""

        flyway(LAST_1X).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', 'acme', 'Letters', 'AUTHORED'), ('system', 'acme', 'System', 'AUTHORED');
                INSERT INTO themes (id, tenant_key, catalog_key, name) VALUES ('brand', 'acme', 'letters', 'Brand');

                INSERT INTO document_templates (id, tenant_key, catalog_key, name, pdfa_enabled, theme_resource_id)
                SELECT 'invoice', 'acme', 'letters', 'Invoice', FALSE, resource_id FROM themes WHERE id = 'brand';
                INSERT INTO template_variants (id, tenant_key, template_resource_id, title, description, attributes, is_default, created_at)
                SELECT x.id, t.tenant_key, t.resource_id, x.title, x.description, x.attributes::jsonb, x.is_default, x.created_at
                FROM document_templates t,
                     (VALUES ('second', 'Second', 'For courts', '{"system.locale":"nl"}', FALSE, TIMESTAMPTZ '2026-01-02 00:00:00+00'),
                             ('default', 'Default', NULL, '{}', TRUE, TIMESTAMPTZ '2026-01-01 00:00:00+00')) AS x(id, title, description, attributes, is_default, created_at);
                -- Both published; the second has a draft since that differs.
                INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at)
                SELECT x.id, t.tenant_key, t.resource_id, x.variant, x.model::jsonb, x.status, x.published_at
                FROM document_templates t,
                     (VALUES (1, 'default', '${model("hello")}', 'published', NOW()),
                             (1, 'second', '${model("hello")}', 'published', NOW()),
                             (2, 'second', '${model("draft")}', 'draft', NULL)) AS x(id, variant, model, status, published_at);
                INSERT INTO contract_versions (id, tenant_key, template_resource_id, data_model, data_examples, status, published_at)
                SELECT 1, t.tenant_key, t.resource_id, '{"type":"object"}'::jsonb,
                       '[{"id":"u1","name":"Standaard brief","data":{"a":1}},{"id":"u2","name":"Standaard brief","data":{"a":2}},{"id":"u3","name":"Kept","data":{},"key":"kept-key"}]'::jsonb,
                       'published', NOW()
                FROM document_templates t;
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            val next = "(SELECT next_version_id FROM catalogs WHERE catalog_key = 'letters')"
            val published = "(SELECT version_id FROM catalog_versions WHERE name = 'Published in 1.x')"
            val payload = mapper.readTree(
                connection.rows("SELECT c.payload::text FROM release_resources r JOIN resource_contents c USING (tenant_key, content_hash) WHERE r.version_id = $next AND r.resource_type = 'template'").single().single(),
            )
            val resource = payload.path("resource")
            assertThat(listOf(resource.path("name").asString(), resource.path("themeKey").asString(), resource.path("pdfaEnabled").asBoolean())).containsExactly("Invoice", "brand", false)
            assertThat(resource.path("themeCatalogKey").isNull).`as`("a theme of its own catalog is named relatively").isTrue()
            assertThat(resource.path("dataExamples").toList().map { it.path("key").asString() }).containsExactly("standaard-brief", "standaard-brief-2", "kept-key")
            assertThat(resource.path("dataExamples").toList().none { it.has("id") }).`as`("no example keeps the id").isTrue()
            assertThat(resource.path("variants").toList().map { it.path("key").asString() }).`as`("the default first").containsExactly("default", "second")
            assertThat(resource.has("templateModel") || resource.path("variants").any { it.has("templateModel") || it.has("title") })
                .`as`("the template's content holds no variant's own fields").isFalse()

            fun part(version: String, key: String) = mapper.readTree(
                connection.rows("SELECT c.payload::text FROM release_resources r JOIN resource_contents c USING (tenant_key, content_hash) WHERE r.version_id = $version AND r.resource_type = 'variant' AND r.resource_key = '$key'").single().single(),
            ).path("resource")
            val second = part(next, "second")
            assertThat(listOf(second.path("title").asString(), second.path("description").asString())).containsExactly("Second", "For courts")
            assertThat(second.path("templateModel").path("nodes").path("t1").path("props").path("content").asString()).`as`("the working copy is the draft").isEqualTo("draft")
            assertThat(second.path("templateModel").path("themeRef").path("themeKey").asString()).`as`("in wire v8").isEqualTo("brand")
            assertThat(part(next, "default").has("description")).`as`("a variant without one has none").isFalse()
            assertThat(part(published, "second").path("templateModel").path("nodes").path("t1").path("props").path("content").asString())
                .`as`("the named draft holds what it published").isEqualTo("hello")

            // Ready: in the next draft what has no differing draft; in the named draft everything.
            assertThat(connection.rows("SELECT m.part FROM resource_ready_marks m WHERE m.version_id = $next AND m.part <> '' ORDER BY 1").map { it.single() }).containsExactly("default")
            assertThat(connection.rows("SELECT m.part FROM resource_ready_marks m WHERE m.version_id = $published AND m.part <> '' ORDER BY 1").map { it.single() }).containsExactly("default", "second")
            assertThat(connection.rows("SELECT count(*) FROM resource_ready_marks m JOIN resource_content_digests d USING (tenant_key, version_id, resource_id, part) WHERE d.digest = m.digest").single().single())
                .`as`("every mark holds against the content").isEqualTo(connection.rows("SELECT count(*) FROM resource_ready_marks").single().single())

            assertThat(connection.rows("SELECT v.name, d.alias FROM catalog_version_dependencies d JOIN catalog_versions v USING (tenant_key, version_id) ORDER BY 1 NULLS FIRST"))
                .`as`("the attribute's qualifier declares its catalog, in both drafts").containsExactly(listOf(null, "system"), listOf("Published in 1.x", "system"))
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
