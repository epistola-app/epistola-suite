// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import db.migration.core.V20261025105700__core_legacy_content
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/**
 * `core_legacy_content` and `core_drop_content_store` (#742): an installation that upgrades before
 * the 1.x content backfill ran has its remaining blobs moved in the migration step: documents into
 * `document_content`, assets into `asset_content` with their hash stamped, before the fonts are
 * converted, so a face whose bytes were still there joins its font; then the legacy table goes. An asset whose legacy bytes are
 * missing is left as it is, never refused.
 *
 * Seeded with raw SQL on purpose: `content_store` is what no command writes any more.
 */
@Tag("integration")
class LegacyContentMigrationTest {

    @Test
    fun `the content hash is SHA-256, lowercase hex`() {
        // FIPS 180-2 test vector.
        assertThat(V20261025105700__core_legacy_content.sha256Hex("abc".toByteArray())).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `remaining blobs move to their stores, a face links to its bytes, missing bytes are left, the table goes`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "legacy_content_migration"
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
        fun sha(hex: String) = MessageDigest.getInstance("SHA-256").digest(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).joinToString("") { "%02x".format(it) }
        val logo = "89504e470101"
        val secret = "89504e470202"
        val face = "0001000000aa"

        flyway(BEFORE_LEGACY_CONTENT).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');

                -- Assets the 1.x backfill never reached: no hash, their bytes in content_store, or none.
                INSERT INTO assets (id, tenant_key, name, media_type, size_bytes, sensitive, catalog_id, version_id)
                SELECT k, c.tenant_key, k, t, 6, s, c.catalog_id, v.version_id
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next,
                     (VALUES ('logo', 'image/png', false), ('secret', 'image/png', true), ('lost', 'image/png', false),
                             ('face', 'font/ttf', false), ('lost-face', 'font/ttf', false)) AS x(k, t, s)
                WHERE c.catalog_key = 'letters';
                INSERT INTO content_store (key, content, content_type, size_bytes) VALUES
                    ('assets/acme/logo', '\x$logo'::bytea, 'image/png', 6),
                    ('assets/acme/secret', '\x$secret'::bytea, 'image/png', 6),
                    ('assets/acme/face', '\x$face'::bytea, 'font/ttf', 6),
                    ('documents/acme/00000000-0000-0000-0000-000000000001', '\x25504446'::bytea, 'application/pdf', 4),
                    ('documents/acme/00000000-0000-0000-0000-0000000000ff', '\x25504446'::bytea, 'application/pdf', 4);

                -- A document whose blob is still in content_store; the second blob's document is gone.
                SELECT epistola_create_partition('documents'::regclass, DATE '2026-03-01');
                INSERT INTO document_templates (id, tenant_key, name, catalog_id, version_id)
                SELECT 'letter', c.tenant_key, 'Letter', c.catalog_id, v.version_id
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';
                INSERT INTO documents (id, tenant_key, catalog_key, template_key, variant_key, version_key, filename, size_bytes, created_at, registry, namespace)
                VALUES ('00000000-0000-0000-0000-000000000001', 'acme', 'letters', 'letter', 'default', 1, 'letter.pdf', 4, TIMESTAMPTZ '2026-03-15 10:00:00+00', 'exchange.epistola.app', 'acme');

                -- A 1.x font whose faces the backfill never reached: neither a face nor its asset has a
                -- hash; one face's bytes are still in content_store, the other's 1.x had lost.
                INSERT INTO fonts (slug, tenant_key, name, kind, catalog_id, version_id)
                SELECT 'house-sans', c.tenant_key, 'House Sans', 'sans', c.catalog_id, v.version_id
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next WHERE c.catalog_key = 'letters';
                INSERT INTO font_variants (tenant_key, version_id, font_resource_id, weight, italic, source, asset_resource_id, content_hash)
                SELECT f.tenant_key, f.version_id, f.resource_id, w, false, 'ASSET', a.resource_id, NULL
                FROM fonts f, (VALUES (400, 'face'), (700, 'lost-face')) AS x(w, k)
                JOIN assets a ON a.id = x.k;
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT id, content_hash FROM assets ORDER BY id"))
                .`as`("each asset with bytes is stamped with their hash; one without is left as it was; the face-only file goes")
                .containsExactly(listOf("logo", sha(logo)), listOf("lost", null), listOf("lost-face", null), listOf("secret", sha(secret)))
            // Moved before the fonts are converted (#742), so the conversion finds the face's hash.
            val face400 = connection.rows("SELECT f.payload -> 'resource' -> 'variants' ->> 0 FROM current_fonts f WHERE f.slug = 'house-sans'").single().single()!!
            assertThat(face400).`as`("the face whose bytes were still in content_store is the font's").contains("\"contentHash\": \"${sha(face)}\"")
            assertThat(connection.rows("SELECT scope, content_hash FROM asset_content ORDER BY content_hash").map { it.joinToString("/") })
                .`as`("under the shared scope, or the tenant's for a sensitive asset")
                .containsExactlyInAnyOrder("global/${sha(logo)}", "acme/${sha(secret)}", "global/${sha(face)}")
            assertThat(connection.rows("SELECT b.scope || '/' || b.binary_hash FROM resource_binaries b JOIN release_resources f ON f.tenant_key = b.tenant_key AND f.content_hash = b.content_hash WHERE f.resource_type = 'font' AND f.resource_key = 'house-sans' AND f.version_id IS NOT NULL").map { it.single() })
                .`as`("the font links to its face's bytes").containsExactly("global/${sha(face)}")
            assertThat(connection.rows("SELECT key FROM document_content").map { it.single() })
                .`as`("the document's blob moves; the one whose document is gone does not")
                .containsExactly("documents/acme/00000000-0000-0000-0000-000000000001")
            assertThat(connection.rows("SELECT CAST(to_regclass('content_store') IS NULL AS TEXT)").single().single()).isEqualTo("true")
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
