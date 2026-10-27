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
 * The 2.0 upgrade's drafts (steps 2 and 4) on a 1.x installation: every catalog's working copy
 * becomes its next draft, unnumbered; its 1.x releases, which kept no content, are not kept, and an
 * authored catalog's draft names the highest as its base, so its first release is numbered after it.
 * A subscribed catalog's draft names the release it installed. A caller that names no version then
 * means the next draft.
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

        flyway(LAST_1X).migrate()
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
                "SELECT c.catalog_key, v.version_id = c.next_version_id, v.version, v.base_version, v.name FROM catalog_versions v " +
                    "JOIN catalogs c ON c.catalog_id = v.catalog_id ORDER BY c.catalog_key",
            )
            assertThat(drafts).`as`("one next draft each; 1.10.0 is the highest by SemVer, not by text").containsExactly(
                listOf("fresh", "t", null, null, null),
                listOf("mirror", "t", null, "3.1.0", null),
                listOf("released", "t", null, "1.10.0", null),
                listOf("waiting", "t", null, null, null),
            )
            assertThat(connection.rows("SELECT CAST(to_regclass('catalog_releases') IS NULL AS TEXT)")).`as`("1.x releases kept no content").containsExactly(listOf("true"))
            assertThat(connection.rows("SELECT count(*) FROM catalog_versions WHERE state <> 'draft' OR version IS NOT NULL"))
                .`as`("no release is cut (ADR 0035)").containsExactly(listOf("0"))

            // The working copy's content is in the next draft, and that is what a caller naming none means.
            val next = connection.rows("SELECT next_version_id FROM catalogs WHERE catalog_key = 'released'").single().single()
            assertThat(connection.rows("SELECT version_id FROM release_resources WHERE resource_type = 'theme' AND resource_key = 'brand'")).containsExactly(listOf(next))
            fun current(catalog: String) = connection.rows(
                "SELECT cv.version_id FROM catalog_current_versions cv JOIN catalogs c ON c.catalog_id = cv.catalog_id WHERE c.catalog_key = '$catalog'",
            ).single().single()
            assertThat(current("released")).isEqualTo(next)

            // Parallel work is reached only by targeting it.
            connection.exec(
                "INSERT INTO catalog_versions (tenant_key, catalog_id, state, base_version, name) SELECT tenant_key, catalog_id, 'draft', '1.0.0', 'Redesign' FROM catalogs WHERE catalog_key = 'released'",
            )
            val parallel = connection.rows("SELECT version_id FROM catalog_versions WHERE name = 'Redesign'").single().single()
            assertThat(current("released")).isEqualTo(next)
            connection.autoCommit = false
            connection.exec("SELECT set_config('epistola.content_version', '$parallel', TRUE)")
            assertThat(current("released")).`as`("unless the transaction targets another").isEqualTo(parallel)
            connection.commit()
            connection.autoCommit = true

            // A draft has no number.
            assertThatThrownBy { connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, state, version, name) SELECT tenant_key, catalog_id, 'draft', '4.0.0', 'Four' FROM catalogs WHERE catalog_key = 'released'") }
                .hasMessageContaining("chk_catalog_versions_draft_unnumbered")
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
}
