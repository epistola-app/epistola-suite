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
 * The 2.0 upgrade's fonts (step 6, ADR 0033): a font's faces become binaries of its content, linked to
 * the bytes `asset_content` already holds, and the asset rows that only held a face's file go. It
 * never refuses (ADR 0035): an image a face used stays an image, and so does a font file an image
 * node names, each face linked to the same bytes. A font was never versioned in 1.x: it is ready.
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

        flyway(LAST_1X).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', 'acme', 'Letters', 'AUTHORED');

                -- The bytes, as uploads stored them: shared, and one tenant-isolated (a sensitive upload).
                INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes) VALUES
                    ('global', '${"a".repeat(64)}', '\x0001'::bytea, 'font/ttf', 2),
                    ('acme',   '${"b".repeat(64)}', '\x0002'::bytea, 'font/ttf', 2),
                    ('global', '${"c".repeat(64)}', '\x0003'::bytea, 'image/png', 2),
                    ('global', '${"d".repeat(64)}', '\x0004'::bytea, 'font/ttf', 2);

                INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes, content_hash, sensitive)
                SELECT k, 'acme', 'letters', k, t, 2, h, s
                FROM (VALUES ('face-regular', 'font/ttf', '${"a".repeat(64)}', false),
                             ('face-bold', 'font/ttf', '${"b".repeat(64)}', true),
                             ('mark', 'image/png', '${"c".repeat(64)}', false),
                             ('named-ttf', 'font/ttf', '${"d".repeat(64)}', false)) AS x(k, t, h, s);

                INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind, created_at, updated_at)
                VALUES ('house-sans', 'acme', 'letters', 'House Sans', 'sans', TIMESTAMPTZ '2026-10-21 12:00:00+00', TIMESTAMPTZ '2026-10-21 12:00:00+00');
                INSERT INTO font_variants (tenant_key, font_resource_id, weight, italic, source, asset_resource_id, content_hash)
                SELECT f.tenant_key, f.resource_id, w, i, 'ASSET', a.resource_id, a.content_hash
                FROM fonts f, (VALUES (400, false, 'face-regular'), (700, false, 'face-bold'), (400, true, 'mark'), (700, true, 'named-ttf')) AS x(w, i, k)
                JOIN assets a ON a.id = x.k;

                -- An image node naming a font file (1.x named it assetId): it stays where it is named.
                INSERT INTO document_templates (id, tenant_key, catalog_key, name) VALUES ('letter', 'acme', 'letters', 'Letter');
                INSERT INTO template_variants (id, tenant_key, title, template_resource_id, is_default)
                SELECT 'default', t.tenant_key, 'Default', t.resource_id, TRUE FROM document_templates t WHERE t.id = 'letter';
                INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at)
                SELECT 1, t.tenant_key, t.resource_id, 'default',
                       '{"modelVersion": 1, "root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["s"]}, "logo": {"id": "logo", "type": "image", "props": {"assetId": "named-ttf"}}}, "slots": {"s": {"id": "s", "nodeId": "root", "name": "children", "children": ["logo"]}}, "themeRef": {"type": "inherit"}}'::jsonb,
                       'published', NOW()
                FROM document_templates t WHERE t.id = 'letter';
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
                    SELECT left(b.binary_hash, 1) FROM release_resources r
                    JOIN resource_binaries b ON b.tenant_key = r.tenant_key AND b.content_hash = r.content_hash
                    WHERE r.resource_type = 'font' ORDER BY 1
                    """,
                ).map { it.single() },
            ).`as`("each face links to its bytes, the tenant-isolated one too (one store since step 6)")
                .containsExactly("a", "b", "c", "d")
            assertThat(connection.rows("SELECT id FROM current_images ORDER BY id").map { it.single() })
                .`as`("the face-only files go; the image a face used, and the file an image node names, stay")
                .containsExactly("mark", "named-ttf")
            assertThat(connection.rows("SELECT count(*) FROM asset_content").single().single()).`as`("no bytes are lost").isEqualTo("4")
            assertThat(
                connection.rows(
                    "SELECT count(*) FROM resource_ready_marks m JOIN resource_content_digests d USING (tenant_key, version_id, resource_id, part) " +
                        "JOIN release_resources r ON r.tenant_key = m.tenant_key AND r.version_id = m.version_id AND r.resource_id = m.resource_id " +
                        "WHERE d.digest = m.digest AND r.resource_type = 'font'",
                ).single().single(),
            ).`as`("the font is ready, its mark holding against its content").isEqualTo("1")
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
