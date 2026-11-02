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
 * The upgrade of a 1.x quality ledger to results by what was checked (ADR 0044,
 * `V20261101002000__quality_results_by_input`): what people said is kept, what a 1.x check computed
 * shows as outdated until the variant is checked again, and what a check no longer reported is gone.
 *
 * Seeded in SQL because the state is a 1.x database's, which no command of this version can make.
 */
@Tag("integration")
class QualityResultsMigrationTest {
    @Test
    fun `people's notes, comments and ignores survive, a 1x open finding is outdated, a resolved one goes`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "quality_results_migration"
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
        connect(url, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute(SEED) } }
        flyway(null).migrate()

        connect(url, postgres.username, postgres.password).use { c ->
            val findings = c.rows("SELECT source_id, fingerprint, status, input_key FROM quality_findings ORDER BY fingerprint")
            assertThat(findings).containsExactly(
                // The open 1.x finding, under a key no version has: outdated until checked again.
                listOf("lint", "f-open", "OPEN", "checked-before-2.0"),
                // People's notes, open and resolved, as they were: no check, no key.
                listOf("manual", "n-open", "OPEN", null),
                listOf("manual", "n-resolved", "RESOLVED", null),
            )
            assertThat(c.rows("SELECT source_id, variant_key, input_key FROM quality_checks"))
                .containsExactly(listOf("lint", "formal", "checked-before-2.0"))

            // Both comments survive, the one on the finding that went included, on the problem.
            assertThat(c.rows("SELECT body, source_id, rule_id, finding_fingerprint, ignore_scope_urn FROM quality_finding_comments ORDER BY body"))
                .containsExactly(
                    listOf("Gone, but discussed", "lint", "rule", "f-resolved", "urn:epistola:template:wide/wide/docs/letter"),
                    listOf("Still there", "lint", "rule", "f-open", "urn:epistola:template:wide/wide/docs/letter"),
                )
            assertThat(c.rows("SELECT finding_fingerprint, reason FROM quality_finding_ignores")).containsExactly(listOf("f-open", "Known"))
        }
    }

    private fun connect(url: String, username: String, password: String): Connection = DriverManager.getConnection(url, username, password)

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
    }

    private companion object {
        /** The last migration of 1.x (`TwoZeroChainEquivalenceIT.LAST_1X`). */
        const val LAST_1X = "20260923150918"

        /**
         * A 1.x tenant whose letter has a variant with an open finding (commented, ignored), a resolved
         * one (commented), and two notes a person raised, one resolved.
         */
        val SEED = """
            INSERT INTO tenants (id, name) VALUES ('wide', 'Wide');
            INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('docs', 'wide', 'Docs', 'AUTHORED');
            INSERT INTO document_templates (id, tenant_key, catalog_key, name) VALUES ('letter', 'wide', 'docs', 'Letter');
            INSERT INTO template_variants (id, tenant_key, template_resource_id, title, attributes, is_default)
            SELECT 'formal', 'wide', resource_id, 'Formal', '{}'::jsonb, TRUE FROM document_templates WHERE id = 'letter';
            INSERT INTO quality_findings (tenant_key, id, source_id, rule_id, severity, subject_urn, subject_type, ignore_scope_urn,
                                          variant_key, message, fingerprint, status, resolved_at, template_resource_id)
            SELECT 'wide', x.id::uuid, x.source, 'rule', 'WARNING', 'urn:epistola:variant:wide/docs/letter/formal', 'VARIANT',
                   'urn:epistola:template:wide/docs/letter', 'formal', 'Check', x.fingerprint, x.status, x.resolved_at, t.resource_id
            FROM document_templates t, (VALUES
                ('00000000-0000-0000-0000-000000000701', 'lint', 'f-open', 'OPEN', NULL::timestamptz),
                ('00000000-0000-0000-0000-000000000702', 'lint', 'f-resolved', 'RESOLVED', NOW()),
                ('00000000-0000-0000-0000-000000000703', 'manual', 'n-open', 'OPEN', NULL::timestamptz),
                ('00000000-0000-0000-0000-000000000704', 'manual', 'n-resolved', 'RESOLVED', NOW())
            ) AS x(id, source, fingerprint, status, resolved_at)
            WHERE t.id = 'letter';
            INSERT INTO quality_finding_comments (tenant_key, finding_id, id, body, author_name) VALUES
                ('wide', '00000000-0000-0000-0000-000000000701', '00000000-0000-0000-0000-000000000711', 'Still there', 'Ann'),
                ('wide', '00000000-0000-0000-0000-000000000702', '00000000-0000-0000-0000-000000000712', 'Gone, but discussed', 'Ann');
            INSERT INTO quality_finding_ignores (tenant_key, ignore_scope_urn, source_id, rule_id, finding_fingerprint, reason, template_resource_id)
            SELECT 'wide', 'urn:epistola:template:wide/docs/letter', 'lint', 'rule', 'f-open', 'Known', resource_id
            FROM document_templates WHERE id = 'letter';
        """.trimIndent()
    }
}
