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
 * `core_working_copy_without_versions` gives each template variant its working model and each
 * template its data contract (the draft where there is one, else the latest published), and marks
 * ready exactly what was publishable before: unfinished drafts stay unready.
 */
@Tag("integration")
class WorkingCopyWithoutVersionsMigrationTest {

    @Test
    fun `the working copy is what the author was working on, and only finished work is ready`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "working_copy_without_versions_migration"
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
            // Rows as 1.x wrote them: no command writes template or contract versions any more.
            connection.createStatement().use { it.execute(SEED) }
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            fun rows(sql: String): Map<String, String?> = connection.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            }

            assertThat(rows("SELECT t.id, v.template_model->>'root' FROM template_variants v JOIN document_templates t ON t.resource_id = v.template_resource_id"))
                .containsExactlyInAnyOrderEntriesOf(
                    mapOf(
                        "published-only" to "published",
                        "draft-over-published" to "draft",
                        "never-published" to "draft",
                        "draft-as-published" to "published",
                        "contract-in-progress" to "published",
                    ),
                )
            assertThat(rows("SELECT id, data_model->>'title' FROM document_templates"))
                .containsEntry("published-only", "published contract")
                .containsEntry("contract-in-progress", "draft contract")
            assertThat(rows("SELECT id, data_examples->0->>'key' FROM document_templates")).containsEntry("published-only", "jane")

            val ready = rows(
                """
                SELECT r.resource_key, m.part FROM resource_ready_marks m
                JOIN catalog_resources r ON r.tenant_key = m.tenant_key AND r.resource_id = m.resource_id
                """,
            )
            assertThat(ready.keys).containsExactlyInAnyOrder("published-only", "draft-as-published", "brand")
            assertThat(ready).containsEntry("published-only", "default").containsEntry("brand", "")
            // And each mark still holds: it is the content's digest now.
            assertThat(
                rows(
                    """
                    SELECT m.part || m.resource_id::text, d.digest FROM resource_ready_marks m
                    LEFT JOIN resource_content_digests d ON d.tenant_key = m.tenant_key AND d.resource_id = m.resource_id
                         AND d.part = m.part AND d.digest = m.digest
                    """,
                ).values,
            ).doesNotContainNull()
        }
    }

    private companion object {
        /** The last migration before the one under test. */
        const val BEFORE = "20261018200500"

        fun model(root: String) = """{"modelVersion": 1, "root": "$root", "nodes": {"$root": {"id": "$root", "type": "root", "slots": []}}, "slots": {}, "themeRef": {"type": "inherit"}}"""

        fun template(key: String) = """
            INSERT INTO document_templates (id, tenant_key, name, resource_id, catalog_id)
                VALUES ('$key', 'acme', '$key', gen_random_uuid(), (SELECT catalog_id FROM catalogs WHERE catalog_key = 'letters'));
            INSERT INTO template_variants (id, tenant_key, title, template_resource_id)
                VALUES ('default', 'acme', 'Default', (SELECT resource_id FROM document_templates WHERE id = '$key'));
        """.trimIndent()

        fun contract(key: String, id: Int, status: String, title: String) = """
            INSERT INTO contract_versions (id, tenant_key, template_resource_id, data_model, data_examples, status)
                VALUES ($id, 'acme', (SELECT resource_id FROM document_templates WHERE id = '$key'),
                        '{"title": "$title"}', '[{"key": "jane", "name": "Jane", "data": {}}]', '$status');
        """.trimIndent()

        fun version(key: String, id: Int, status: String, root: String) = """
            INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status)
                VALUES ($id, 'acme', (SELECT resource_id FROM document_templates WHERE id = '$key'), 'default', '${model(root)}', '$status');
        """.trimIndent()

        val SEED = listOf(
            "INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');",
            """INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type, catalog_metadata)
               VALUES ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED', '{}');""",
            template("published-only"), contract("published-only", 1, "published", "published contract"),
            version("published-only", 1, "published", "published"),
            template("draft-over-published"), contract("draft-over-published", 1, "published", "c"),
            version("draft-over-published", 1, "published", "published"), version("draft-over-published", 2, "draft", "draft"),
            template("never-published"), contract("never-published", 1, "draft", "c"),
            version("never-published", 1, "draft", "draft"),
            template("draft-as-published"), contract("draft-as-published", 1, "published", "c"),
            version("draft-as-published", 1, "published", "published"), version("draft-as-published", 2, "draft", "published"),
            template("contract-in-progress"), contract("contract-in-progress", 1, "published", "published contract"),
            contract("contract-in-progress", 2, "draft", "draft contract"),
            version("contract-in-progress", 1, "published", "published"),
            """INSERT INTO themes (id, tenant_key, name, document_styles, resource_id, catalog_id)
               VALUES ('brand', 'acme', 'Brand', '{}', gen_random_uuid(), (SELECT catalog_id FROM catalogs WHERE catalog_key = 'letters'));""",
            """INSERT INTO stencils (id, tenant_key, name, tags, resource_id, catalog_id, content)
               VALUES ('header', 'acme', 'Header', '[]', gen_random_uuid(), (SELECT catalog_id FROM catalogs WHERE catalog_key = 'letters'), '${model("draft")}');""",
            """INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, status)
               VALUES (1, 'acme', (SELECT resource_id FROM stencils WHERE id = 'header'), '${model("draft")}', 'draft');""",
        ).joinToString("\n")
    }
}
