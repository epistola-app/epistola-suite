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
 * `core_drafts_numbered_at_release` (contract ADR 0002, amendment 2) takes the number off every draft
 * and keeps everything else: the highest-numbered draft, the one callers meant, becomes the catalog's
 * next version; any other becomes long-term work, named after its old number. Content stays in the
 * draft it was in, bases stay, and an installed release keeps its number.
 */
@Tag("integration")
class DraftsNumberedAtReleaseMigrationTest {

    @Test
    fun `drafts lose their number and keep their content, the highest becoming the next version and the rest named long-term work`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "drafts_numbered_at_release_migration"
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
            // Rows as the schema before this migration holds them: numbered drafts, which no command
            // of today writes.
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type) VALUES
                    ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED'),
                    ('fresh', 'acme', 'exchange.epistola.app', 'acme', 'Fresh', 'AUTHORED'),
                    ('mirror', 'acme', 'exchange.epistola.app', 'epistola', 'Mirror', 'SUBSCRIBED');
                UPDATE catalogs SET installed_release_version = '3.1.0' WHERE catalog_key = 'mirror';
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, manifest_snapshot)
                SELECT 'acme', catalog_id, '1.0.0', 'v5:' || repeat('0', 64), '{}' FROM catalogs WHERE catalog_key = 'letters';
                INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind, base_version)
                SELECT tenant_key, catalog_id, v.version, 'draft', v.base FROM catalogs
                CROSS JOIN (VALUES ('1.0.1', '1.0.0'), ('1.10.0', '1.0.0'), ('1.9.0', '1.0.0')) v(version, base)
                WHERE catalog_key = 'letters';
                INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind)
                SELECT tenant_key, catalog_id, '1.0.0', 'draft' FROM catalogs WHERE catalog_key = 'fresh';
                INSERT INTO themes (id, tenant_key, catalog_id, version_id, name)
                SELECT 'brand', 'acme', v.catalog_id, v.version_id, 'Brand ' || v.version
                FROM catalog_versions v JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE c.catalog_key = 'letters';
                """.trimIndent(),
            )
        }
        val before = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.rows("SELECT v.version, v.version_id FROM catalog_versions v WHERE v.kind = 'draft'").associate { it[0]!! to it[1]!! }
        }

        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            val drafts = connection.rows(
                "SELECT c.catalog_key, v.version_id, v.is_next, v.name, v.version, v.base_version FROM catalog_versions v " +
                    "JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE v.kind = 'draft' ORDER BY c.catalog_key, v.is_next DESC, v.name",
            )
            assertThat(drafts).containsExactly(
                // 1.10.0 is the highest by SemVer, not by text: the next version.
                listOf("fresh", before.getValue("1.0.0"), "t", null, null, null),
                listOf("letters", before.getValue("1.10.0"), "t", null, null, "1.0.0"),
                listOf("letters", before.getValue("1.0.1"), "f", "Version 1.0.1", null, "1.0.0"),
                listOf("letters", before.getValue("1.9.0"), "f", "Version 1.9.0", null, "1.0.0"),
            )
            // Each draft keeps its own content.
            assertThat(connection.rows("SELECT version_id, name FROM themes ORDER BY name").map { it[0] to it[1] }).containsExactly(
                before.getValue("1.0.1") to "Brand 1.0.1",
                before.getValue("1.10.0") to "Brand 1.10.0",
                before.getValue("1.9.0") to "Brand 1.9.0",
            )
            assertThat(connection.rows("SELECT v.version FROM catalog_versions v JOIN catalogs c ON c.catalog_id = v.catalog_id WHERE c.catalog_key = 'mirror'"))
                .`as`("an installed release keeps its number").containsExactly(listOf("3.1.0"))

            // A caller that names no version means the next version, whatever else is open.
            fun current(catalog: String) = connection.rows(
                "SELECT cv.version_id FROM catalog_current_versions cv JOIN catalogs c ON c.catalog_id = cv.catalog_id WHERE c.catalog_key = '$catalog'",
            ).single().single()
            assertThat(current("letters")).isEqualTo(before.getValue("1.10.0"))
            connection.autoCommit = false
            connection.exec("SELECT set_config('epistola.content_version', '${before.getValue("1.9.0")}', TRUE)")
            assertThat(current("letters")).`as`("unless the transaction targets another").isEqualTo(before.getValue("1.9.0"))
            connection.commit()
            connection.autoCommit = true

            // One next version per catalog; a draft has no number; long-term work has a name.
            assertThatThrownBy { connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, kind, is_next) SELECT tenant_key, catalog_id, 'draft', TRUE FROM catalogs WHERE catalog_key = 'letters'") }
                .hasMessageContaining("uq_catalog_versions_one_next")
            assertThatThrownBy { connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, kind, version, name) SELECT tenant_key, catalog_id, 'draft', '4.0.0', 'Four' FROM catalogs WHERE catalog_key = 'letters'") }
                .hasMessageContaining("chk_catalog_versions_numbered")
            assertThatThrownBy { connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, kind) SELECT tenant_key, catalog_id, 'draft' FROM catalogs WHERE catalog_key = 'letters'") }
                .hasMessageContaining("chk_catalog_versions_long_term_named")
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** Every row of [sql], each column as text (booleans as `t`/`f`), nulls kept. */
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery("SELECT row_to_json(q)::text FROM ($sql) q").use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        Regex(""""[^"]+":("(?:[^"\\]|\\.)*"|true|false|null|[^,}]+)""").findAll(rs.getString(1)).map { m ->
                            when (val v = m.groupValues[1]) {
                                "null" -> null
                                "true" -> "t"
                                "false" -> "f"
                                else -> v.removeSurrounding("\"")
                            }
                        }.toList(),
                    )
                }
            }
        }
    }

    private companion object {
        /** The last migration before the one under test. */
        const val BEFORE = "20261017181204"
    }
}
