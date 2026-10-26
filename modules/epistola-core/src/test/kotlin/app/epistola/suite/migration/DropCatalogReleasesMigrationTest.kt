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
 * `core_drop_catalog_releases` (ADR 0036, step (e)): `catalog_releases`, its mirror and the emptied
 * pins table go. A release's merge parents name its version row, a publication names the version row
 * by its catalog and number, and both still go with the release. The catalog's current release is a
 * view over its released rows, not three columns on `catalogs`, and the next version is the
 * catalog's pointer alone, checked at commit.
 *
 * Seeded with raw SQL on purpose: the fixture must match the schema before the migration.
 */
@Tag("integration")
class DropCatalogReleasesMigrationTest {

    @Test
    fun `a release is only its version row, and what hung from catalog_releases hangs from it`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "drop_catalog_releases_migration"
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

        val hash = "v6:${"1".repeat(64)}"
        flyway(BEFORE_DROP_CATALOG_RELEASES).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters');
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, version_key, fingerprint, content_hash, manifest_snapshot)
                SELECT tenant_key, catalog_id, '1.1.0', 'letters-merged', '$hash', '$hash', '{}'::jsonb FROM catalogs;
                -- A release cut here: the current release is the highest of those.
                UPDATE catalog_versions SET origin = 'authored' WHERE state = 'released';
                INSERT INTO catalog_release_merges (tenant_key, catalog_id, version, merged_version, merged_fingerprint)
                SELECT tenant_key, catalog_id, '1.1.0', '1.0.1', '$hash' FROM catalogs;
                INSERT INTO catalog_release_publications (id, tenant_key, catalog_id, version, fingerprint, namespace, status, idempotency_key)
                SELECT gen_random_uuid(), tenant_key, catalog_id, '1.1.0', '$hash', 'acme', 'READY', gen_random_uuid() FROM catalogs;
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(
                connection.rows(
                    """
                    SELECT v.version, m.merged_version FROM catalog_release_merges m
                    JOIN catalog_versions v ON v.tenant_key = m.tenant_key AND v.version_id = m.version_id
                    """,
                ),
            ).`as`("a merge parent names the release's row").containsExactly(listOf("1.1.0", "1.0.1"))
            assertThat(
                connection.rows(
                    "SELECT table_name FROM information_schema.tables WHERE table_name IN ('catalog_releases', 'catalog_release_dependencies')",
                ),
            ).`as`("the old tables are gone").isEmpty()

            assertThat(connection.rows("SELECT version FROM catalog_current_releases"))
                .`as`("the catalog's current release is a query over its released rows").containsExactly(listOf("1.1.0"))
            assertThat(
                connection.rows(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'catalogs' AND column_name IN ('released_version', 'released_fingerprint', 'released_at')
                    """,
                ),
            ).`as`("the pointer columns are gone").isEmpty()

            assertThat(connection.rows("SELECT column_name FROM information_schema.columns WHERE table_name = 'catalog_versions' AND column_name = 'is_next'"))
                .`as`("next is the catalog's pointer only").isEmpty()
            assertThatThrownBy {
                connection.exec("UPDATE catalogs SET next_version_id = (SELECT version_id FROM catalog_versions WHERE state = 'released')")
            }.`as`("the next version is one of the catalog's drafts").hasMessageContaining("is not one of its drafts")
            assertThatThrownBy {
                connection.exec("INSERT INTO catalog_versions (tenant_key, catalog_id, state) SELECT tenant_key, catalog_id, 'draft' FROM catalogs")
            }.`as`("a draft without a name is the catalog's next").hasMessageContaining("without a name is not its next version")

            connection.exec("DELETE FROM catalog_versions WHERE state = 'released' AND version = '1.1.0'")
            assertThat(connection.rows("SELECT count(*) FROM catalog_current_releases")).`as`("no release, no current release").containsExactly(listOf("0"))
            assertThat(connection.rows("SELECT count(*) FROM catalog_release_merges")).`as`("merge parents go with the release").containsExactly(listOf("0"))
            assertThat(connection.rows("SELECT count(*) FROM catalog_release_publications")).`as`("publications go with the release").containsExactly(listOf("0"))
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
