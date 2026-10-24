// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager

/**
 * `core_dependency_aliases` and `core_references_by_alias` (contract ADR 0003, amendment of
 * 2026-10-22; ADR 0034): every declaration, of a draft and of a release, gets its catalog's key as
 * its alias, and stored content that an earlier draft of wire v8 let name a catalog's namespace and
 * registry loses them, keeping the `catalogKey` that is its alias now, so it resolves as it did.
 * Nothing that was ready stops being ready, nothing reads as edited, and a qualifier that named no
 * catalog this tenant has under that key is reported.
 *
 * Seeded with raw SQL on purpose: the old shape (declarations without an alias, references with
 * qualifiers) is exactly what no command writes any more.
 */
@Tag("integration")
class ReferencesByAliasMigrationTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `declarations get their key as alias, stored references lose their qualifiers and resolve as before`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "references_by_alias_migration"
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
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('parts', 'acme', 'registry.example.com', 'parts-shop', 'Parts');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('system', 'acme', 'exchange.epistola.app', 'epistola', 'System');

                INSERT INTO catalog_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, position)
                SELECT c.tenant_key, c.catalog_id, v.version_id, 'registry.example.com', 'parts-shop', 'parts', 0
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';
                INSERT INTO catalog_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, position)
                SELECT c.tenant_key, c.catalog_id, v.version_id, 'exchange.epistola.app', 'epistola', 'system', 1
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';

                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, manifest_snapshot)
                SELECT tenant_key, catalog_id, '1.0.0', 'v5:${"0".repeat(64)}', '{}'::jsonb FROM catalogs WHERE catalog_key = 'letters';
                INSERT INTO catalog_release_dependencies (tenant_key, catalog_id, version, registry, namespace, dependency_key, position, release_version, release_fingerprint)
                SELECT tenant_key, catalog_id, '1.0.0', 'registry.example.com', 'parts-shop', 'parts', 0, '1.0.0', 'v5:${"1".repeat(64)}' FROM catalogs WHERE catalog_key = 'letters';
                """.trimIndent(),
            )
            connection.exec(
                """
                INSERT INTO document_templates (id, tenant_key, name, catalog_id, version_id)
                SELECT 'letter', c.tenant_key, 'Letter', c.catalog_id, v.version_id
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';
                INSERT INTO template_variants (id, tenant_key, title, template_resource_id, template_model, version_id, updated_at)
                SELECT 'default', t.tenant_key, 'Default', t.resource_id, '${mapper.writeValueAsString(QUALIFIED_MODEL)}'::jsonb, t.version_id, TIMESTAMPTZ '2026-10-21 12:00:00+00'
                FROM document_templates t WHERE t.id = 'letter';
                INSERT INTO stencils (id, tenant_key, name, catalog_id, version_id, content, updated_at)
                SELECT 'footer', c.tenant_key, 'Footer', c.catalog_id, v.version_id, '${mapper.writeValueAsString(QUALIFIED_STENCIL)}'::jsonb, TIMESTAMPTZ '2026-10-21 12:00:00+00'
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';
                INSERT INTO themes (id, tenant_key, name, catalog_id, version_id, document_styles, updated_at)
                SELECT 'brand', c.tenant_key, 'Brand', c.catalog_id, v.version_id, '{"fontFamily": {"key": "inter", "catalogKey": "system", "namespace": "epistola", "registry": "exchange.epistola.app"}}'::jsonb,
                       TIMESTAMPTZ '2026-10-21 12:00:00+00'
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';

                -- Each resource marked ready with its content as it was: the marks hold.
                INSERT INTO resource_ready_marks (tenant_key, resource_id, part, digest, version_id)
                SELECT tenant_key, resource_id, part, digest, version_id FROM resource_content_digests WHERE tenant_key = 'acme';
                """.trimIndent(),
            )
        }
        // Up to the themes table's last schema: what follows moves themes to JSON storage.
        flyway(BEFORE_THEME_STORAGE).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT dependency_key, alias FROM catalog_dependencies ORDER BY position"))
                .`as`("every declaration declares its catalog under its key")
                .containsExactly(listOf("parts", "parts"), listOf("system", "system"))
            assertThat(connection.rows("SELECT dependency_key, alias FROM catalog_release_dependencies"))
                .containsExactly(listOf("parts", "parts"))

            val model = mapper.readTree(connection.rows("SELECT template_model::text FROM template_variants").single().single())
            val nodes = model.get("nodes")
            assertThat(mapper.convertValue(nodes.get("header").get("props"), Map::class.java))
                .`as`("a stencil instance keeps its catalog's key, its alias, and loses the name")
                .isEqualTo(mapOf("stencilKey" to "header", "catalogKey" to "parts"))
            assertThat(mapper.convertValue(model.get("themeRef"), Map::class.java)).isEqualTo(mapOf("type" to "override", "themeKey" to "default", "catalogKey" to "system"))
            assertThat(mapper.convertValue(nodes.get("title").get("styles").get("fontFamily"), Map::class.java)).isEqualTo(mapOf("key" to "inter", "catalogKey" to "system"))
            assertThat(mapper.convertValue(nodes.get("misnamed").get("props"), Map::class.java)).isEqualTo(mapOf("stencilKey" to "badge", "catalogKey" to "parts"))
            assertThat(nodes.get("plain").get("props").toString()).`as`("a node that is not a reference keeps its props").contains("namespace")

            val stencil = mapper.readTree(connection.rows("SELECT content::text FROM stencils").single().single())
            assertThat(mapper.convertValue(stencil.get("documentStylesOverride").get("fontFamily"), Map::class.java)).isEqualTo(mapOf("key" to "inter", "catalogKey" to "system"))
            assertThat(connection.rows("SELECT document_styles::text FROM themes").single().single()).doesNotContain("namespace").doesNotContain("registry").contains("\"catalogKey\": \"system\"")

            assertThat(connection.rows("SELECT count(*) FROM resource_ready_marks m JOIN resource_content_digests d USING (tenant_key, version_id, resource_id, part) WHERE d.digest = m.digest"))
                .`as`("every mark still holds against the content as it is now")
                .containsExactly(listOf("3"))
            assertThat(connection.rows("SELECT updated_at = TIMESTAMPTZ '2026-10-21 12:00:00+00' FROM template_variants UNION ALL SELECT updated_at = TIMESTAMPTZ '2026-10-21 12:00:00+00' FROM stencils UNION ALL SELECT updated_at = TIMESTAMPTZ '2026-10-21 12:00:00+00' FROM themes"))
                .`as`("dropping a qualifier is not an edit")
                .containsOnly(listOf("t"))

            val report = connection.rows("SELECT value::text FROM app_metadata WHERE key = 'references-by-alias'").single().single()
            assertThat(report).contains("named catalog 'parts' in registry.example.com/other-shop").contains("keeps resolving by key")
            assertThat(mapper.readTree(report).size()).`as`("only the reference that named another catalog is reported").isEqualTo(1)
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

    private companion object {
        /** The migration before `core_dependency_aliases`. */
        const val BEFORE = "20261022052418"

        /** A template document as an earlier draft of wire v8 let it be stored: references qualified. */
        val QUALIFIED_MODEL = mapOf(
            "modelVersion" to 1,
            "root" to "root",
            "themeRef" to mapOf("type" to "override", "themeKey" to "default", "catalogKey" to "system", "namespace" to "epistola"),
            "nodes" to mapOf(
                "root" to mapOf("id" to "root", "type" to "root", "slots" to listOf("root-slot")),
                "title" to mapOf(
                    "id" to "title",
                    "type" to "text",
                    "styles" to mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to "system", "namespace" to "epistola")),
                ),
                "header" to mapOf(
                    "id" to "header",
                    "type" to "stencil",
                    "slots" to emptyList<String>(),
                    "props" to mapOf("stencilKey" to "header", "catalogKey" to "parts", "namespace" to "parts-shop", "registry" to "registry.example.com"),
                ),
                // Names a namespace the tenant's 'parts' is not in: still the tenant's 'parts', reported.
                "misnamed" to mapOf(
                    "id" to "misnamed",
                    "type" to "stencil",
                    "slots" to emptyList<String>(),
                    "props" to mapOf("stencilKey" to "badge", "catalogKey" to "parts", "namespace" to "other-shop", "registry" to "registry.example.com"),
                ),
                // A prop of that name on a node that is no reference is not a qualifier.
                "plain" to mapOf("id" to "plain", "type" to "text", "props" to mapOf("namespace" to "kept")),
            ),
            "slots" to mapOf(
                "root-slot" to mapOf("id" to "root-slot", "nodeId" to "root", "name" to "children", "children" to listOf("title", "header", "misnamed", "plain")),
            ),
        )

        val QUALIFIED_STENCIL = mapOf(
            "modelVersion" to 1,
            "root" to "root",
            "documentStylesOverride" to mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to "system", "namespace" to "epistola", "registry" to "exchange.epistola.app")),
            "nodes" to mapOf("root" to mapOf("id" to "root", "type" to "root", "slots" to emptyList<String>())),
            "slots" to emptyMap<String, Any>(),
        )
    }
}
