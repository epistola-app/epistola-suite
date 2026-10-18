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
 * The 2.0 catalog identity migrations (`core_catalog_identity`, `core_catalog_id`) on data shaped
 * like an installation upgrading from before them: every catalog gets its name and an opaque id,
 * and every row that belonged to a catalog by key belongs to the same catalog by id afterwards.
 *
 * Seeded with raw SQL on purpose: commands speak only the current schema, and this fixture must
 * match the schema as it was before these migrations.
 */
@Tag("integration")
class CatalogIdentityMigrationTest {

    @Test
    fun `existing catalogs are named and every row follows its catalog by id`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "catalog_identity_migration"
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

        flyway(BEFORE_IDENTITY).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use(::seed)
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(rows(connection, "SELECT tenant_key, catalog_key, registry, namespace FROM catalogs ORDER BY 1, 2"))
                .containsExactly(
                    listOf("ab", "default", "exchange.epistola.app", "ab0"),
                    listOf("acme", "default", "exchange.epistola.app", "acme"),
                    listOf("acme", "letters", "exchange.epistola.app", "acme"),
                    listOf("acme", "system", "exchange.epistola.app", "epistola"),
                )
            assertThat(rows(connection, "SELECT count(DISTINCT catalog_id) FROM catalogs")).containsExactly(listOf("4"))

            // Each row reaches the catalog it named by key, now by id.
            val owners = """
                SELECT 'theme', t.id, c.catalog_key FROM themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                UNION ALL
                SELECT 'resource', r.resource_key, c.catalog_key FROM catalog_resources r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
                ORDER BY 1, 2
            """.trimIndent()
            assertThat(rows(connection, owners)).containsExactly(
                listOf("resource", "corporate", "letters"),
                listOf("resource", "plain", "default"),
                listOf("theme", "corporate", "letters"),
                listOf("theme", "plain", "default"),
            )
        }
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme'), ('ab', 'AB');
                INSERT INTO catalogs (id, tenant_key, name, type, source_url) VALUES
                    ('default', 'acme', 'Default', 'AUTHORED', NULL),
                    ('letters', 'acme', 'Letters', 'AUTHORED', NULL),
                    ('system', 'acme', 'System', 'SUBSCRIBED', 'classpath:epistola/catalogs/system/catalog.json'),
                    ('default', 'ab', 'Default', 'AUTHORED', NULL);
                INSERT INTO themes (id, tenant_key, catalog_key, name) VALUES
                    ('plain', 'acme', 'default', 'Plain'),
                    ('corporate', 'acme', 'letters', 'Corporate');
                """.trimIndent(),
            )
        }
    }

    private fun rows(connection: Connection, sql: String): List<List<String?>> = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map { rs.getString(it) }) }
        }
    }

    private companion object {
        /** The last migration of 1.x (v1.5.0): what an upgrade to 2.0 starts from. */
        const val BEFORE_IDENTITY = "20260923150918"
    }
}
