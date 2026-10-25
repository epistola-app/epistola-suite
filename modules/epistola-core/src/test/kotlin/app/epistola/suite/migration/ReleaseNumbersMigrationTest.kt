// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `core_release_numbers_128` (contract ADR 0003, amendment of 2026-10-22; ADR 0034): every column that
 * holds a release number takes up to 128 characters, so a patch release of any namespace fits, and
 * the generated ordering columns read a patch number's core and label. The releases there were keep
 * their numbers and order, and the trigger that moves a next version on an installed release still
 * fires.
 *
 * Seeded with raw SQL on purpose: the fixture must match the schema before the migration.
 */
@Tag("integration")
class ReleaseNumbersMigrationTest {

    @Test
    fun `release numbers grow to 128 characters and patch releases order as SemVer does`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "release_numbers_migration"
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
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, content_hash, manifest_snapshot)
                SELECT tenant_key, catalog_id, v, 'v6:${"0".repeat(64)}', 'v6:${"0".repeat(64)}', '{}'::jsonb FROM catalogs, unnest(ARRAY['1.2.0', '1.10.0', '5.5']) AS v WHERE catalog_key = 'letters';
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            val long = "1.3.1-m" + "a".repeat(62) + ".1"
            connection.exec(
                """
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, content_hash, manifest_snapshot)
                SELECT tenant_key, catalog_id, v, 'v6:${"1".repeat(64)}', 'v6:${"1".repeat(64)}', '{}'::jsonb FROM catalogs,
                       unnest(ARRAY['1.3.1', '1.3.0', '1.3.1-gemeente-x.2', '1.3.1-gemeente-x.10', '1.3.1-bureau.9', '$long']) AS v WHERE catalog_key = 'letters';
                """.trimIndent(),
            )
            assertThat(connection.rows("SELECT version FROM catalog_releases ORDER BY $ORDER").map { it.single() })
                .`as`("by SemVer precedence, a label below its core, labels as ASCII and then by number")
                .containsExactly("1.10.0", "1.3.1", long, "1.3.1-gemeente-x.10", "1.3.1-gemeente-x.2", "1.3.1-bureau.9", "1.3.0", "1.2.0", "5.5")
            assertThat(connection.rows("SELECT version_major, version_minor, version_patch, version_label, version_label_number FROM catalog_releases WHERE version = '1.3.1-gemeente-x.2'"))
                .containsExactly(listOf("1", "3", "1", "gemeente-x", "2"))
            assertThat(connection.rows("SELECT version_major FROM catalog_releases WHERE version = '5.5'")).`as`("a label from before 2.0 has none").containsExactly(listOf(null))

            // The installed release moves the next version on, as it did, also to a 128-character number.
            connection.exec("UPDATE catalogs SET installed_release_version = '$long' WHERE catalog_key = 'letters'")
            assertThat(connection.rows("SELECT base_version FROM catalog_versions WHERE is_next")).containsExactly(listOf(long))
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
        /** The migration before `core_release_numbers_128`. */
        const val BEFORE = "20261022104231"

        /** What `releaseNumberOrder` orders by, highest first. */
        const val ORDER = "version_major DESC NULLS LAST, version_minor DESC NULLS LAST, version_patch DESC NULLS LAST, " +
            "(version_label IS NULL) DESC, version_label COLLATE \"C\" DESC NULLS LAST, version_label_number DESC NULLS LAST"
    }
}
