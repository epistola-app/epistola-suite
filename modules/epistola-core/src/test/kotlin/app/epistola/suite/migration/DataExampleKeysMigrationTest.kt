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
 * `core_data_example_keys` (a Kotlin migration) keys stored data examples exactly as the contract's
 * v7-to-v8 migration keys a catalog's: derived from the name, in document order, unique within the
 * template, keeping a key an example already has.
 */
@Tag("integration")
class DataExampleKeysMigrationTest {

    @Test
    fun `stored data examples get the keys the contract would give them`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "data_example_keys_migration"
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
        DriverManager.getConnection(url, postgres.username, postgres.password).use(::seed)
        flyway(BEFORE_VERSIONS_DROPPED).migrate()

        val examples = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT data_examples::text FROM contract_versions").use { rs ->
                    rs.next()
                    JsonMapper.builder().build().readTree(rs.getString(1)).iterator().asSequence().toList()
                }
            }
        }
        assertThat(examples.map { it["name"].asString() to it["key"].asString() }).containsExactly(
            "Ärger & Co." to "arger-co",
            "Standaard brief" to "standaard-brief-2",
            "Standaard brief" to "standaard-brief-3",
            "Kept" to "standaard-brief",
        )
        assertThat(examples.map { it["id"].asString() }).containsExactly("e1", "e2", "e3", "e4")
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type)
                    VALUES ('default', 'acme', 'exchange.epistola.app', 'acme', 'Default', 'AUTHORED');
                INSERT INTO document_templates (id, tenant_key, catalog_id, name)
                    VALUES ('invoice', 'acme', (SELECT catalog_id FROM catalogs WHERE tenant_key = 'acme'), 'Invoice');
                INSERT INTO contract_versions (id, tenant_key, template_resource_id, status, data_examples)
                    VALUES (1, 'acme', (SELECT resource_id FROM document_templates WHERE tenant_key = 'acme'), 'draft', '[
                        {"id": "e1", "name": "Ärger & Co.", "data": {}},
                        {"id": "e2", "name": "Standaard brief", "data": {}},
                        {"id": "e3", "name": "Standaard brief", "data": {}},
                        {"id": "e4", "name": "Kept", "key": "standaard-brief", "data": {}}
                    ]');
                """.trimIndent(),
            )
        }
    }

    private companion object {
        /** The last migration before `core_data_example_keys`. */
        const val BEFORE = "20261018200200"

        /** The last migration that still has the 1.x version tables this migration rewrote; the upgrade drops them. */
        const val BEFORE_VERSIONS_DROPPED = "20261018201300"
    }
}
