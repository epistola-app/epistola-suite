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
 * The 2.0 upgrade's namespace grants (step 1, ADR 0034, section 3): every 1.x tenant holds the
 * namespaces of the catalogs it authored, and those its Exchange connection grants. Not those of the
 * catalogs it received (from Epistola, the Exchange, a container registry, a manifest URL, or as a
 * 1.x subscribed copy).
 *
 * Seeded with raw SQL on purpose: a 1.x database is exactly what no command writes.
 */
@Tag("integration")
class NamespaceGrantsMigrationTest {

    @Test
    fun `each tenant holds the namespaces of the catalogs it authors and of its Exchange connection, and nothing it received`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "namespace_grants_migration"
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
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme'), ('beta', 'Beta'), ('idle', 'Idle');

                -- acme authors these: two named in its own namespace, and one bound for publishing to an Exchange namespace.
                INSERT INTO catalogs (id, tenant_key, name, type) VALUES
                    ('letters', 'acme', 'Letters', 'AUTHORED'),
                    ('drafts', 'acme', 'Drafts', 'AUTHORED'),
                    ('forms', 'acme', 'Forms', 'AUTHORED');
                INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace) VALUES ('acme', 'forms', 'acme-forms');
                -- ... and receives these: Epistola's, the Exchange's, a container registry's, and a 1.x subscribed copy.
                INSERT INTO catalogs (id, tenant_key, name, source_url, type) VALUES
                    ('system', 'acme', 'System', 'classpath:epistola/catalogs/system/catalog.json', 'SUBSCRIBED'),
                    ('received', 'acme', 'Received', 'exchange:partner/received', 'SUBSCRIBED'),
                    ('mirrored', 'acme', 'Mirrored', 'oci:harbor/supplier/mirrored', 'AUTHORED'),
                    ('subscribed', 'acme', 'Subscribed', NULL, 'SUBSCRIBED');

                -- acme's Exchange connection grants public-services, and a value that is no namespace;
                -- beta's grants acme, which beta also authors a catalog in: one grant, the connection's.
                INSERT INTO exchange_tenant_connections (tenant_key, issuer, base_url, authorization_request_endpoint, token_endpoint, namespaces, status, updated_at) VALUES
                    ('acme', 'https://exchange.example', 'https://exchange.example', 'https://exchange.example/a', 'https://exchange.example/t', ARRAY['public-services', 'Not a slug'], 'ACTIVE', TIMESTAMPTZ '2026-10-01 09:00:00+00'),
                    ('beta', 'https://exchange.example', 'https://exchange.example', 'https://exchange.example/a', 'https://exchange.example/t', ARRAY['acme'], 'REAUTHORIZATION_REQUIRED', TIMESTAMPTZ '2026-10-02 09:00:00+00');
                INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('shared', 'beta', 'Shared', 'AUTHORED');
                INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace) VALUES ('beta', 'shared', 'acme');

                -- idle has only Epistola's catalog.
                INSERT INTO catalogs (id, tenant_key, name, source_url, type) VALUES
                    ('system', 'idle', 'System', 'classpath:epistola/catalogs/system/catalog.json', 'SUBSCRIBED');
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT tenant_key, authority, namespace, granted_through, granted_by FROM tenant_namespace_grants ORDER BY tenant_key, namespace, authority"))
                .containsExactly(
                    listOf("acme", "exchange.epistola.app", "acme", "EXISTING", null),
                    listOf("acme", "exchange.epistola.app", "acme-forms", "EXISTING", null),
                    listOf("acme", "exchange.epistola.app", "public-services", "EXCHANGE_CONNECTION", null),
                    listOf("beta", "exchange.epistola.app", "acme", "EXCHANGE_CONNECTION", null),
                )
            assertThat(connection.rows("SELECT granted_at = TIMESTAMPTZ '2026-10-01 09:00:00+00' FROM tenant_namespace_grants WHERE namespace = 'public-services'"))
                .`as`("a connection's grant is dated when the connection last changed").containsExactly(listOf("t"))
            assertThat(connection.rows("SELECT code FROM namespace_grant_sources ORDER BY code"))
                .containsExactly(listOf("ADMINISTRATOR"), listOf("EXCHANGE_CONNECTION"), listOf("EXISTING"))
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
