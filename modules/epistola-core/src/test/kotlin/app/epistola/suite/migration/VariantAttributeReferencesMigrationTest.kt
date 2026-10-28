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
 * A template's variants name the attributes they select by, and since ADR 0039 those are references
 * in `resource_references`, indexed as a template content is stored. Content stored before is
 * indexed by `V20261101001800`, by the same rule: `catalog.attribute` names that catalog by its
 * alias, a bare key no alias. It adds index rows only, and a content holding no variant attributes
 * gets none.
 *
 * Seeded with raw SQL on purpose: the state is the one before the migration, which no command
 * writes any more (a command now indexes the references itself).
 */
@Tag("integration")
class VariantAttributeReferencesMigrationTest {

    @Test
    fun `the attributes of stored templates' variants are indexed, enveloped and bare, and nothing else changes`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "variant_attribute_references_migration"
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
                INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES
                    -- In the envelope, as 2.0 stores it, with its theme already indexed.
                    ('acme', '${"a".repeat(64)}', 'template',
                     '{"resource": {"key": "letter", "name": "Letter", "type": "template", "themeKey": "brand",
                       "variants": [{"key": "default", "attributes": {"system.language": "nl"}, "isDefault": true},
                                    {"key": "formal", "attributes": {"system.language": "en", "tone": "formal"}, "isDefault": false}]}}'),
                    -- Retained before templates were stored in the envelope: the resource bare.
                    ('acme', '${"b".repeat(64)}', 'template',
                     '{"key": "memo", "name": "Memo", "type": "template", "variants": [{"key": "default", "attributes": {"tone": "casual"}}]}'),
                    -- No variant attributes at all, and variants that are not objects.
                    ('acme', '${"c".repeat(64)}', 'template',
                     '{"resource": {"key": "plain", "name": "Plain", "type": "template", "variants": [{"key": "default", "attributes": null}, "odd"]}}'),
                    -- Another kind, whose JSON happens to hold variants: not a template, not walked.
                    ('acme', '${"d".repeat(64)}', 'variant',
                     '{"resource": {"title": "Default", "variants": [{"attributes": {"tone": "x"}}]}}');
                INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key)
                VALUES ('acme', '${"a".repeat(64)}', NULL, 'theme', 'brand');
                """.trimIndent(),
            )
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            assertThat(
                connection.rows(
                    """
                    SELECT left(content_hash, 1), COALESCE(alias, '-'), resource_type, resource_key FROM resource_references
                    ORDER BY 1, resource_type COLLATE "C", COALESCE(alias, '-') COLLATE "C", resource_key COLLATE "C"
                    """,
                ),
            ).`as`("each attribute a variant names, once per content, beside what was indexed before")
                .containsExactly(
                    listOf("a", "-", "attribute", "tone"),
                    listOf("a", "system", "attribute", "language"),
                    listOf("a", "-", "theme", "brand"),
                    listOf("b", "-", "attribute", "tone"),
                )
            assertThat(connection.rows("SELECT count(*) FROM resource_contents").single().single()).`as`("no content changes").isEqualTo("4")
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
        /** The last migration before the variant attributes were indexed. */
        const val BEFORE = "20261101001700"
    }
}
