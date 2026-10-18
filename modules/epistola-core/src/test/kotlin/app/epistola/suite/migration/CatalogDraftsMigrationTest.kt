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
 * `core_catalog_versions` and `core_upgrade_to_2_0` on a 1.x installation: every authored catalog's
 * working copy becomes its next version, unnumbered; its 1.x releases, which kept no content, are
 * removed, and the highest is recorded for the upgrade to number the first release after. A subscribed
 * catalog's content is its installed release. A caller that names no version then means the next
 * version.
 *
 * Seeded with raw SQL on purpose: commands speak only the current schema, and this fixture must
 * match the schema of 1.x.
 */
@Tag("integration")
class CatalogDraftsMigrationTest {

    @Test
    fun `every authored catalog gets its next version, and its 1_x releases make way for a first release after them`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "catalog_drafts_migration"
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
                INSERT INTO catalogs (id, tenant_key, name, type, source_url, installed_release_version) VALUES
                    ('released', 'acme', 'Released', 'AUTHORED', NULL, NULL),
                    ('fresh', 'acme', 'Fresh', 'AUTHORED', NULL, NULL),
                    ('mirror', 'acme', 'Mirror', 'SUBSCRIBED', 'classpath:epistola/catalogs/system/catalog.json', '3.1.0'),
                    ('waiting', 'acme', 'Waiting', 'SUBSCRIBED', 'classpath:epistola/catalogs/system/catalog.json', NULL);
                INSERT INTO catalog_releases (tenant_key, catalog_key, version, fingerprint, manifest_snapshot)
                SELECT 'acme', 'released', v, repeat('0', 64), '{}' FROM unnest(ARRAY['1.0.0', '1.10.0', '1.9.3']) v;
                UPDATE catalogs SET released_version = '1.10.0', released_fingerprint = repeat('0', 64), released_at = NOW() WHERE id = 'released';
                INSERT INTO themes (id, tenant_key, catalog_key, name) VALUES ('brand', 'acme', 'released', 'Brand');
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            val drafts = connection.rows(
                "SELECT c.catalog_key, v.is_next, v.version, v.base_version, v.name FROM catalog_versions v " +
                    "JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE v.kind = 'draft' ORDER BY c.catalog_key",
            )
            assertThat(drafts).containsExactly(
                listOf("fresh", "t", null, null, null),
                listOf("released", "t", null, null, null),
            )
            assertThat(connection.rows("SELECT count(*) FROM catalog_releases")).`as`("1.x releases kept no content").containsExactly(listOf("0"))
            assertThat(connection.rows("SELECT catalog_key, released_version, released_fingerprint, released_at FROM catalogs WHERE released_version IS NOT NULL OR released_fingerprint IS NOT NULL OR released_at IS NOT NULL"))
                .`as`("no catalog points at a removed release").isEmpty()
            assertThat(
                connection.rows(
                    "SELECT c.catalog_key, q.continues_after FROM catalog_upgrade_queue q JOIN catalogs c ON c.catalog_id = q.catalog_id WHERE c.type = 'AUTHORED' ORDER BY 1",
                ),
            ).`as`("1.10.0 is the highest by SemVer, not by text").containsExactly(listOf("fresh", null), listOf("released", "1.10.0"))
            assertThat(
                connection.rows(
                    "SELECT c.catalog_key, v.version FROM catalog_versions v JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE v.kind = 'installed' ORDER BY 1",
                ),
            ).`as`("a subscribed catalog's content is its installed release, 0.0.0 until it installs one")
                .containsExactly(listOf("mirror", "3.1.0"), listOf("waiting", "0.0.0"))

            // The working copy's content is in the next version, and that is what a caller naming none means.
            val next = connection.rows(
                "SELECT v.version_id FROM catalog_versions v JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE c.catalog_key = 'released' AND v.is_next",
            ).single().single()
            assertThat(connection.rows("SELECT version_id FROM themes WHERE id = 'brand'")).containsExactly(listOf(next))
            fun current(catalog: String) = connection.rows(
                "SELECT cv.version_id FROM catalog_current_versions cv JOIN catalogs c ON c.catalog_id = cv.catalog_id WHERE c.catalog_key = '$catalog'",
            ).single().single()
            assertThat(current("released")).isEqualTo(next)

            // Long-term work is reached only by targeting it.
            connection.exec(
                "INSERT INTO catalog_versions (tenant_key, catalog_id, kind, base_version, name) SELECT tenant_key, catalog_id, 'draft', '1.0.0', 'Redesign' FROM catalogs WHERE catalog_key = 'released'",
            )
            val longTerm = connection.rows("SELECT version_id FROM catalog_versions WHERE name = 'Redesign'").single().single()
            assertThat(current("released")).isEqualTo(next)
            connection.autoCommit = false
            connection.exec("SELECT set_config('epistola.content_version', '$longTerm', TRUE)")
            assertThat(current("released")).`as`("unless the transaction targets another").isEqualTo(longTerm)
            connection.commit()
            connection.autoCommit = true

            // One next version per catalog; a draft has no number; long-term work has a name.
            assertThatThrownBy { connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, kind, is_next) SELECT tenant_key, catalog_id, 'draft', TRUE FROM catalogs WHERE catalog_key = 'released'") }
                .hasMessageContaining("uq_catalog_versions_one_next")
            assertThatThrownBy { connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, kind, version, name) SELECT tenant_key, catalog_id, 'draft', '4.0.0', 'Four' FROM catalogs WHERE catalog_key = 'released'") }
                .hasMessageContaining("chk_catalog_versions_numbered")
            assertThatThrownBy { connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, kind) SELECT tenant_key, catalog_id, 'draft' FROM catalogs WHERE catalog_key = 'released'") }
                .hasMessageContaining("chk_catalog_versions_long_term_named")
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** Every row of [sql], each column as text (booleans as `t`/`f`), nulls kept. */
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map { rs.getString(it) }) }
        }
    }

    private companion object {
        /** The last migration of 1.x (v1.5.0): what an upgrade to 2.0 starts from. */
        const val BEFORE = "20260923150918"
    }
}
