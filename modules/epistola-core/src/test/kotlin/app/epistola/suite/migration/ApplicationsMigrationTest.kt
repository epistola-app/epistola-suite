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
 * The upgrade to applications (ADR 0054, section 3): every key that is not revoked becomes an
 * application of its own, with its name and roles, seeing every document, so it can do and see
 * exactly what it could before. A revoked key gets none.
 *
 * Seeded with raw SQL on purpose: the keys are in the shape before applications, which no command
 * writes any more.
 */
@Tag("integration")
class ApplicationsMigrationTest {

    @Test
    fun `each key that is not revoked becomes a tenant-wide application with its roles`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "applications_migration"
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

        flyway(BEFORE_APPLICATIONS).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO api_keys (id, tenant_key, name, key_hash, key_prefix, enabled, roles) VALUES
                    ('01900000-0000-7000-8000-000000000001', 'acme', 'Case system', 'h1', 'epk_a', true, ARRAY['CONTENT_VIEWER','DOCUMENT_GENERATOR']),
                    ('01900000-0000-7000-8000-000000000002', 'acme', 'Registry', 'h2', 'epk_b', true, ARRAY['CONTENT_VIEWER']);
                INSERT INTO api_keys (id, tenant_key, name, key_hash, key_prefix, enabled, roles, revoked_at) VALUES
                    ('01900000-0000-7000-8000-000000000003', 'acme', 'Old', 'h3', 'epk_c', false, ARRAY['CONTENT_VIEWER'], NOW());
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(
                connection.rows(
                    """
                    SELECT a.id, a.tenant_key, a.name, array_to_string(a.roles, ','), a.sees_all_documents
                    FROM applications a ORDER BY a.id
                    """,
                ),
            ).`as`("one application per active key, taking its id, name and roles, seeing every document").containsExactly(
                listOf("01900000-0000-7000-8000-000000000001", "acme", "Case system", "CONTENT_VIEWER,DOCUMENT_GENERATOR", "t"),
                listOf("01900000-0000-7000-8000-000000000002", "acme", "Registry", "CONTENT_VIEWER", "t"),
            )
            assertThat(connection.rows("SELECT id, application_id FROM api_keys ORDER BY id"))
                .`as`("each active key belongs to its application; the revoked one to none")
                .containsExactly(
                    listOf("01900000-0000-7000-8000-000000000001", "01900000-0000-7000-8000-000000000001"),
                    listOf("01900000-0000-7000-8000-000000000002", "01900000-0000-7000-8000-000000000002"),
                    listOf("01900000-0000-7000-8000-000000000003", null),
                )
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map { rs.getString(it) }) }
        }
    }

    private companion object {
        /** The last migration before applications. */
        const val BEFORE_APPLICATIONS = "20261108213332"
    }
}
