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
 * `core_fonts_to_storage` and `core_drop_fonts` (ADR 0033, step 5): a font's faces become binaries
 * of its content, linked to the bytes `asset_content` already holds, and the asset rows that only
 * held a face's file go. It never refuses (ADR 0035): an image a face used stays an image, and so
 * does a font file an image node names, each face linked to the same bytes.
 *
 * Seeded with raw SQL on purpose: the tables are the 1.x shape no command writes any more.
 */
@Tag("integration")
class FontsToStorageMigrationTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `faces become the font's binaries, face-only assets go, an image a face used stays`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "fonts_to_storage_migration"
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

        flyway(BEFORE_FONT_STORAGE).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');

                -- The bytes, as uploads stored them: shared, and one tenant-isolated (a sensitive upload).
                INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes) VALUES
                    ('global', '${"a".repeat(64)}', '\x0001'::bytea, 'font/ttf', 2),
                    ('acme',   '${"b".repeat(64)}', '\x0002'::bytea, 'font/ttf', 2),
                    ('global', '${"c".repeat(64)}', '\x0003'::bytea, 'image/png', 2),
                    ('global', '${"d".repeat(64)}', '\x0004'::bytea, 'font/ttf', 2);

                INSERT INTO assets (id, tenant_key, name, media_type, size_bytes, content_hash, sensitive, catalog_id, version_id)
                SELECT k, c.tenant_key, k, t, 2, h, s, c.catalog_id, v.version_id
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next,
                     (VALUES ('face-regular', 'font/ttf', '${"a".repeat(64)}', false),
                             ('face-bold', 'font/ttf', '${"b".repeat(64)}', true),
                             ('mark', 'image/png', '${"c".repeat(64)}', false),
                             ('named-ttf', 'font/ttf', '${"d".repeat(64)}', false)) AS x(k, t, h, s)
                WHERE c.catalog_key = 'letters';

                INSERT INTO fonts (slug, tenant_key, name, kind, catalog_id, version_id, created_at, updated_at)
                SELECT 'house-sans', c.tenant_key, 'House Sans', 'sans', c.catalog_id, v.version_id, TIMESTAMPTZ '2026-10-21 12:00:00+00', TIMESTAMPTZ '2026-10-21 12:00:00+00'
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';
                INSERT INTO font_variants (tenant_key, version_id, font_resource_id, weight, italic, source, asset_resource_id, content_hash)
                SELECT f.tenant_key, f.version_id, f.resource_id, w, i, 'ASSET', a.resource_id, a.content_hash
                FROM fonts f, (VALUES (400, false, 'face-regular'), (700, false, 'face-bold'), (400, true, 'mark'), (700, true, 'named-ttf')) AS x(w, i, k)
                JOIN assets a ON a.id = x.k;

                -- An image node naming a font file: it stays where it is named.
                INSERT INTO document_templates (id, tenant_key, name, catalog_id, version_id)
                SELECT 'letter', c.tenant_key, 'Letter', c.catalog_id, v.version_id
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';
                INSERT INTO template_variants (id, tenant_key, title, template_resource_id, template_model, version_id)
                SELECT 'default', t.tenant_key, 'Default', t.resource_id,
                       '{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["s"]}, "logo": {"id": "logo", "type": "image", "props": {"imageKey": "named-ttf"}}}, "slots": {"s": {"id": "s", "nodeId": "root", "name": "children", "children": ["logo"]}}}'::jsonb,
                       t.version_id
                FROM document_templates t WHERE t.id = 'letter';

                -- The font marked ready: the mark holds.
                INSERT INTO resource_ready_marks (tenant_key, resource_id, part, digest, version_id)
                SELECT tenant_key, resource_id, part, digest, version_id FROM resource_content_digests d
                WHERE tenant_key = 'acme' AND resource_id = (SELECT resource_id FROM fonts);
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            val payload = mapper.readTree(connection.rows("SELECT payload::text FROM current_fonts WHERE slug = 'house-sans'").single().single())
            assertThat(payload.path("resource").path("variants").toList().map { "${it.path("weight").asInt()}/${it.path("italic").asBoolean()}/${it.path("mediaType").asString()}" })
                .`as`("every face, by italic then weight, with what its file is")
                .containsExactly("400/false/font/ttf", "700/false/font/ttf", "400/true/image/png", "700/true/font/ttf")
            assertThat(
                connection.rows(
                    """
                    SELECT b.scope || '/' || left(b.binary_hash, 1) FROM release_resources r
                    JOIN resource_binaries b ON b.tenant_key = r.tenant_key AND b.content_hash = r.content_hash
                    WHERE r.resource_type = 'font' ORDER BY 1
                    """,
                ).map { it.single() },
            ).`as`("each face links to its bytes where they are, the tenant-isolated one too")
                .containsExactly("acme/b", "global/a", "global/c", "global/d")
            assertThat(connection.rows("SELECT id FROM assets ORDER BY id").map { it.single() })
                .`as`("the face-only files go; the image a face used, and the file an image node names, stay")
                .containsExactly("mark", "named-ttf")
            assertThat(connection.rows("SELECT count(*) FROM asset_content").single().single()).`as`("no bytes are lost").isEqualTo("4")
            assertThat(connection.rows("SELECT count(*) FROM resource_ready_marks m JOIN resource_content_digests d USING (tenant_key, version_id, resource_id, part) WHERE d.digest = m.digest").single().single())
                .`as`("the font's mark holds against its content").isEqualTo("1")
            assertThat(connection.rows("SELECT CAST(to_regclass('fonts') IS NULL AND to_regclass('font_variants') IS NULL AS TEXT)").single().single()).isEqualTo("true")
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
