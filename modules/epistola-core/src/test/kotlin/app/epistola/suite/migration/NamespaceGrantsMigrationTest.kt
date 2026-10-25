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
 * `core_namespace_grants` (ADR 0034, section 3): every existing tenant holds the namespaces of the
 * catalogs it authors, and those its Exchange connection grants. Not those of the catalogs it
 * received (from the Exchange, a registry, a manifest URL, a file another installation released, or
 * as a 1.x subscribed copy), nor Epistola's.
 *
 * Seeded with raw SQL on purpose: a database from before grants is exactly what no command writes.
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

        flyway(BEFORE).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            val here = connection.rows("SELECT value ->> 'id' FROM app_metadata WHERE key = 'installation'").single().single()
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme'), ('beta', 'Beta'), ('idle', 'Idle');

                -- acme authors these: made here (one released here, one never released), one under its own domain,
                -- and one whose release says nothing of where it was cut (recorded before releases named their installation).
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES
                    ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters'),
                    ('drafts', 'acme', 'exchange.epistola.app', 'acme', 'Drafts'),
                    ('forms', 'acme', 'gemeente-x.nl', 'acme-forms', 'Forms'),
                    ('older', 'acme', 'exchange.epistola.app', 'legacy-org', 'Older');
                -- ... and receives these: Epistola's, the Exchange's, a container registry's, a 1.x subscribed copy,
                -- and a file another installation released.
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, source_url, type) VALUES
                    ('system', 'acme', 'exchange.epistola.app', 'epistola', 'System', 'classpath:epistola/catalogs/system/catalog.json', 'SUBSCRIBED'),
                    ('received', 'acme', 'exchange.epistola.app', 'partner', 'Received', 'exchange:partner/received', 'SUBSCRIBED'),
                    ('mirrored', 'acme', 'registry.gemeente-x.nl', 'supplier', 'Mirrored', 'oci:harbor/supplier/mirrored', 'AUTHORED'),
                    ('subscribed', 'acme', 'exchange.epistola.app', 'sub-org', 'Subscribed', NULL, 'SUBSCRIBED'),
                    ('filed', 'acme', 'exchange.epistola.app', 'other-org', 'Filed', NULL, 'AUTHORED');

                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, content_hash, manifest_snapshot, released_by_installation)
                SELECT tenant_key, catalog_id, '1.0.0', 'v5:${"0".repeat(64)}', 'v6:${"0".repeat(64)}', '{}'::jsonb, '$here'::uuid FROM catalogs WHERE catalog_key = 'letters';
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, content_hash, manifest_snapshot, released_by_installation)
                SELECT tenant_key, catalog_id, '1.0.0', 'v5:${"1".repeat(64)}', 'v6:${"1".repeat(64)}', '{}'::jsonb, NULL FROM catalogs WHERE catalog_key = 'older';
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, content_hash, manifest_snapshot, released_by_installation)
                SELECT tenant_key, catalog_id, '1.0.0', 'v5:${"2".repeat(64)}', 'v6:${"2".repeat(64)}', '{}'::jsonb, '0198c0de-0000-7000-8000-000000000001'::uuid FROM catalogs WHERE catalog_key = 'filed';

                -- acme's Exchange connection grants public-services, and a value that is no namespace;
                -- beta's grants acme, which beta also authors a catalog in: one grant, the connection's.
                INSERT INTO exchange_tenant_connections (tenant_key, issuer, base_url, authorization_request_endpoint, token_endpoint, namespaces, status, updated_at) VALUES
                    ('acme', 'https://exchange.example', 'https://exchange.example', 'https://exchange.example/a', 'https://exchange.example/t', ARRAY['public-services', 'Not a slug'], 'ACTIVE', TIMESTAMPTZ '2026-10-01 09:00:00+00'),
                    ('beta', 'https://exchange.example', 'https://exchange.example', 'https://exchange.example/a', 'https://exchange.example/t', ARRAY['acme'], 'REAUTHORIZATION_REQUIRED', TIMESTAMPTZ '2026-10-02 09:00:00+00');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name) VALUES ('shared', 'beta', 'exchange.epistola.app', 'acme', 'Shared');

                -- idle has only Epistola's catalog.
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, source_url) VALUES
                    ('system', 'idle', 'exchange.epistola.app', 'epistola', 'System', 'classpath:epistola/catalogs/system/catalog.json');
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(connection.rows("SELECT tenant_key, authority, namespace, granted_through, granted_by FROM tenant_namespace_grants ORDER BY tenant_key, namespace, authority"))
                .containsExactly(
                    listOf("acme", "exchange.epistola.app", "acme", "EXISTING", null),
                    listOf("acme", "gemeente-x.nl", "acme-forms", "EXISTING", null),
                    listOf("acme", "exchange.epistola.app", "legacy-org", "EXISTING", null),
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

    private companion object {
        /** The migration before `core_namespace_grants`. */
        const val BEFORE = "20261022110352"
    }
}
