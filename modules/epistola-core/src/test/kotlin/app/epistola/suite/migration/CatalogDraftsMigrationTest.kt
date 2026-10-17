// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * `core_catalog_drafts` gives every authored catalog the draft it is working on: the patch after its
 * latest release, on that release, or 1.0.0 when it was never released. A subscribed catalog has none.
 */
@Tag("integration")
class CatalogDraftsMigrationTest {

    @Test
    fun `every authored catalog gets its draft after its latest release`() {
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
            // Rows as an installation before 2.0 has them; no command writes a release without a draft any more.
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                    INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type, catalog_metadata) VALUES
                        ('released', 'acme', 'exchange.epistola.app', 'acme', 'Released', 'AUTHORED', '{}'),
                        ('fresh', 'acme', 'exchange.epistola.app', 'acme', 'Fresh', 'AUTHORED', '{}'),
                        ('mirror', 'acme', 'exchange.epistola.app', 'epistola', 'Mirror', 'SUBSCRIBED', '{}');
                    INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, manifest_snapshot)
                    SELECT 'acme', catalog_id, v, 'v5:' || repeat('0', 64), '{}'
                    FROM catalogs, unnest(ARRAY['1.0.0', '1.10.0', '1.9.3']) v WHERE catalog_key IN ('released', 'mirror');
                    """.trimIndent(),
                )
            }
        }
        flyway(null).migrate()

        val drafts = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery(
                    "SELECT c.catalog_key, d.version, d.base_version FROM catalog_versions d JOIN catalogs c ON c.catalog_id = d.catalog_id WHERE d.kind = 'draft'",
                ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2) to rs.getString(3)) } }
            }
        }

        assertThat(drafts).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                // 1.10.0 is the latest by SemVer, not by text.
                "released" to ("1.10.1" to "1.10.0"),
                "fresh" to ("1.0.0" to null),
            ),
        )
        val installed = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery(
                    "SELECT c.catalog_key, d.version FROM catalog_versions d JOIN catalogs c ON c.catalog_id = d.catalog_id WHERE d.kind = 'installed'",
                ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            }
        }
        assertThat(installed).`as`("a subscribed catalog's content is its installed release; none installed yet").containsExactlyEntriesOf(mapOf("mirror" to "0.0.0"))
    }

    private companion object {
        /** The last migration before the one under test. */
        const val BEFORE = "20261016232900"
    }
}
