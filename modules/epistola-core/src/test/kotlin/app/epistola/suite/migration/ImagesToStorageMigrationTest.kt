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
 * `core_one_asset_scope`, `core_images_to_storage` and `core_drop_assets` (ADR 0033, step 6):
 * bytes a tenant kept apart (a sensitive image, #751) move into the one store, deduplicated, every
 * link following; each image becomes its content naming its bytes, linked; the table goes. An image
 * whose bytes 1.x had lost is kept without them. A ready mark holds against the content.
 *
 * Seeded with raw SQL on purpose: the `assets` table and the scoped store are what no command writes
 * any more.
 */
@Tag("integration")
class ImagesToStorageMigrationTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `bytes share one store, images become content linked to them, the table goes`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "images_to_storage_migration"
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
        val shared = "a".repeat(64)
        val secret = "b".repeat(64)
        val both = "c".repeat(64)

        flyway(BEFORE_IMAGE_STORAGE).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');

                INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes) VALUES
                    ('global', '$shared', '\x01'::bytea, 'image/png', 1),
                    ('acme', '$secret', '\x0202'::bytea, 'image/png', 2),
                    ('acme', '$both', '\x03'::bytea, 'image/png', 1),
                    ('global', '$both', '\x03'::bytea, 'image/png', 1);

                INSERT INTO assets (id, tenant_key, name, media_type, size_bytes, width, height, content_hash, sensitive, catalog_id, version_id)
                SELECT k, c.tenant_key, n, 'image/png', 1, w, w, h, s, c.catalog_id, v.version_id
                FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id AND v.is_next,
                     (VALUES ('logo', 'Logo', 16, '$shared', false), ('seal', 'Seal', NULL, '$secret', true), ('lost', 'Lost', NULL, NULL, false)) AS x(k, n, w, h, s)
                WHERE c.catalog_key = 'letters';

                -- A release's content linked to tenant-scoped bytes, and to the same bytes shared.
                INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES ('acme', '${"e".repeat(64)}', 'image', '{"key": "x"}'::jsonb);
                INSERT INTO resource_binaries (tenant_key, content_hash, scope, binary_hash) VALUES
                    ('acme', '${"e".repeat(64)}', 'acme', '$secret'),
                    ('acme', '${"e".repeat(64)}', 'acme', '$both'),
                    ('acme', '${"e".repeat(64)}', 'global', '$both');

                -- The logo marked ready: the mark holds.
                INSERT INTO resource_ready_marks (tenant_key, resource_id, part, digest, version_id)
                SELECT tenant_key, resource_id, part, digest, version_id FROM resource_content_digests
                WHERE tenant_key = 'acme' AND resource_id = (SELECT resource_id FROM assets WHERE id = 'logo');
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT content_hash FROM asset_content ORDER BY 1").map { it.single() })
                .`as`("every tenant's bytes in the one store, each once").containsExactly(shared, secret, both)
            assertThat(connection.rows("SELECT binary_hash FROM resource_binaries WHERE content_hash = '${"e".repeat(64)}' ORDER BY 1").map { it.single() })
                .`as`("the release's links follow, not doubled").containsExactly(secret, both)

            val logo = mapper.readTree(connection.rows("SELECT payload::text FROM current_images WHERE id = 'logo'").single().single())
            assertThat(mapper.convertValue(logo.path("resource"), Map::class.java))
                .isEqualTo(mapOf("key" to "logo", "name" to "Logo", "mediaType" to "image/png", "width" to 16, "height" to 16, "contentHash" to shared, "type" to "image"))
            assertThat(connection.rows("SELECT id, content_hash, size_bytes::text FROM current_images ORDER BY id"))
                .`as`("each image names its bytes and reads their size; one without bytes is kept without")
                .containsExactly(listOf("logo", shared, "1"), listOf("lost", null, null), listOf("seal", secret, "2"))
            assertThat(
                connection.rows(
                    """
                    SELECT i.id FROM current_images i
                    JOIN release_resources r ON r.tenant_key = i.tenant_key AND r.version_id = i.version_id AND r.resource_type = 'image' AND r.resource_key = i.id
                    JOIN resource_binaries b ON b.tenant_key = r.tenant_key AND b.content_hash = r.content_hash
                    ORDER BY 1
                    """,
                ).map { it.single() },
            ).`as`("each image's content links to its bytes").containsExactly("logo", "seal")
            assertThat(connection.rows("SELECT count(*) FROM resource_ready_marks m JOIN resource_content_digests d USING (tenant_key, version_id, resource_id, part) WHERE d.digest = m.digest").single().single())
                .`as`("the logo's mark holds against its content").isEqualTo("1")
            assertThat(connection.rows("SELECT CAST(to_regclass('assets') IS NULL AS TEXT)").single().single()).isEqualTo("true")
            assertThat(connection.rows("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('asset_content', 'resource_binaries') AND column_name = 'scope'").single().single())
                .`as`("the scope is gone").isEqualTo("0")
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
