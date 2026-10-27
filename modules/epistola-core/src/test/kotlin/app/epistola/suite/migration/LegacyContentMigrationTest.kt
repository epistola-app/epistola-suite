// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import db.migration.core.frozen.CanonicalJson
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 2.0 upgrade's legacy blobs (step 5, #742): an installation that upgrades before the 1.x content
 * backfill ran has its remaining blobs moved in the migration step: documents into `document_content`,
 * files into `asset_content` with their hash stamped, before the fonts and images are converted
 * (step 6), so a face whose bytes were still there joins its font; then the legacy table goes (step
 * 14). An asset whose legacy bytes are missing is left as it is, never refused.
 *
 * Seeded with raw SQL on purpose: the 1.x tables and `content_store` are what no command writes any more.
 */
@Tag("integration")
class LegacyContentMigrationTest {

    @Test
    fun `the content hash is SHA-256, lowercase hex`() {
        // FIPS 180-2 test vector.
        assertThat(CanonicalJson.sha256Hex("abc".toByteArray())).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
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

        flyway(LAST_1X).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', 'acme', 'Letters', 'AUTHORED');

                -- Assets the 1.x backfill never reached: no hash, their bytes in content_store, or none.
                INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes, sensitive)
                VALUES ('logo', 'acme', 'letters', 'logo', 'image/png', 6, false),
                       ('secret', 'acme', 'letters', 'secret', 'image/png', 6, true),
                       ('lost', 'acme', 'letters', 'lost', 'image/png', 6, false),
                       ('face', 'acme', 'letters', 'face', 'font/ttf', 6, false),
                       ('lost-face', 'acme', 'letters', 'lost-face', 'font/ttf', 6, false);
                INSERT INTO content_store (key, content, content_type, size_bytes) VALUES
                    ('assets/acme/logo', '\x$logo'::bytea, 'image/png', 6),
                    ('assets/acme/secret', '\x$secret'::bytea, 'image/png', 6),
                    ('assets/acme/face', '\x$face'::bytea, 'font/ttf', 6),
                    ('documents/acme/00000000-0000-0000-0000-000000000001', '\x25504446'::bytea, 'application/pdf', 4),
                    ('documents/acme/00000000-0000-0000-0000-0000000000ff', '\x25504446'::bytea, 'application/pdf', 4);

                -- A document whose blob is still in content_store; the second blob's document is gone.
                SELECT epistola_create_partition('documents'::regclass, DATE '2026-03-01');
                INSERT INTO document_templates (id, tenant_key, catalog_key, name) VALUES ('letter', 'acme', 'letters', 'Letter');
                INSERT INTO documents (id, tenant_key, catalog_key, template_key, variant_key, version_key, filename, size_bytes, created_at)
                VALUES ('00000000-0000-0000-0000-000000000001', 'acme', 'letters', 'letter', 'default', 1, 'letter.pdf', 4, TIMESTAMPTZ '2026-03-15 10:00:00+00');

                -- A 1.x font whose faces the backfill never reached: neither a face nor its asset has a
                -- hash; one face's bytes are still in content_store, the other's 1.x had lost.
                INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind) VALUES ('house-sans', 'acme', 'letters', 'House Sans', 'sans');
                INSERT INTO font_variants (tenant_key, font_resource_id, weight, italic, source, asset_resource_id, content_hash)
                SELECT f.tenant_key, f.resource_id, w, false, 'ASSET', a.resource_id, NULL
                FROM fonts f, (VALUES (400, 'face'), (700, 'lost-face')) AS x(w, k)
                JOIN assets a ON a.id = x.k;
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT id, content_hash FROM current_images ORDER BY id"))
                .`as`("each image with bytes names their hash; one without is kept without; no font file is an image")
                .containsExactly(listOf("logo", sha(logo)), listOf("lost", null), listOf("secret", sha(secret)))
            assertThat(connection.rows("SELECT id FROM current_images WHERE media_type NOT LIKE 'image/%'")).isEmpty()
            // Moved before the fonts are converted (#742), so the conversion finds the face's hash.
            val face400 = connection.rows("SELECT f.payload -> 'resource' -> 'variants' ->> 0 FROM current_fonts f WHERE f.slug = 'house-sans'").single().single()!!
            assertThat(face400).`as`("the face whose bytes were still in content_store is the font's").contains("\"contentHash\": \"${sha(face)}\"")
            assertThat(connection.rows("SELECT content_hash FROM asset_content").map { it.single() })
                .`as`("each moved, once (a sensitive asset's too, one store since step 6)")
                .containsExactlyInAnyOrder(sha(logo), sha(secret), sha(face))
            assertThat(connection.rows("SELECT DISTINCT b.binary_hash FROM resource_binaries b JOIN release_resources f ON f.tenant_key = b.tenant_key AND f.content_hash = b.content_hash WHERE f.resource_type = 'font' AND f.resource_key = 'house-sans'").map { it.single() })
                .`as`("the font links to its face's bytes").containsExactly(sha(face))
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
