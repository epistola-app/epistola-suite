// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * A 1.x template published while using a stencil 1.x never published (a template could use a
 * stencil's draft): the "Published in 1.x" draft keeps the template, so it holds the stencil too, as
 * the next draft has it, and is releasable. Found by `OneXUpgradeModelTest` after 2.0.0-alpha.1, whose
 * databases `docs/upgrades/2.0.0-alpha.1-repair.sql` mends.
 *
 * Seeded with raw SQL on purpose: commands speak only the current schema, and this fixture must
 * match the schema of 1.x.
 */
@Tag("integration")
class PublishedInOneXStencilsMigrationTest {

    @Test
    fun `the Published in 1x draft holds a never-published stencil its published template uses`() {
        upgraded("published_in_1x_stencils", withUnpublishedWork = true).use { connection ->
            assertThat(held(connection, "'Published in 1.x'")).`as`("the named draft holds the stencil its template uses")
                .contains(listOf("stencil", "header"), listOf("template", "invoice"))
            assertThat(held(connection, "NULL")).contains(listOf("stencil", "header"), listOf("template", "invoice"))
        }
    }

    @Test
    fun `a Published in 1x draft that differed only by that stencil is not kept`() {
        upgraded("published_in_1x_stencils_same", withUnpublishedWork = false).use { connection ->
            assertThat(held(connection, "'Published in 1.x'")).`as`("holding what the next draft holds, it has nothing of its own to release").isEmpty()
            assertThat(held(connection, "NULL")).contains(listOf("stencil", "header"), listOf("template", "invoice"))
        }
    }

    @Test
    fun `the alpha_1 repair script mends a database alpha_1 migrated`() {
        upgraded("published_in_1x_stencils_alpha1", withUnpublishedWork = true).use { connection ->
            // As alpha.1 left it: step 9 recorded under its checksum then, and the named draft without the
            // stencil. Triggers are off for that one statement: they refuse removing what the draft uses.
            connection.createStatement().use {
                it.execute(
                    """
                    UPDATE flyway_schema_history SET checksum = $ALPHA_1_STEP_9 WHERE version = '20261101000900';
                    SET session_replication_role = replica;
                    DELETE FROM release_resources r USING catalog_versions v
                    WHERE v.tenant_key = r.tenant_key AND v.version_id = r.version_id AND v.name = 'Published in 1.x' AND r.resource_type = 'stencil';
                    SET session_replication_role = DEFAULT;
                    """.trimIndent(),
                )
            }
            assertThat(validates(connection)).`as`("a later version refuses the changed step 9").isFalse()
            assertThat(held(connection, "'Published in 1.x'")).doesNotContain(listOf("stencil", "header"))

            connection.createStatement().use { it.execute("BEGIN; ${Files.readString(repairScript())} COMMIT;") }

            assertThat(validates(connection)).`as`("a later version starts").isTrue()
            assertThat(held(connection, "'Published in 1.x'")).contains(listOf("stencil", "header"), listOf("template", "invoice"))
            assertThat(readyStencils(connection)).`as`("the stencil is ready, as in the next draft").isEqualTo(1)
        }
    }

    private fun validates(connection: Connection): Boolean {
        val postgres = TestRuntimeLifecycle.postgres()
        return Flyway.configure().dataSource(connection.metaData.url, postgres.username, postgres.password)
            .locations("classpath:db/migration").load().validateWithResult().validationSuccessful
    }

    private fun readyStencils(connection: Connection): Int = connection.createStatement().use { st ->
        st.executeQuery(
            """
            SELECT count(*) FROM resource_ready_marks m
            JOIN release_resources r ON r.tenant_key = m.tenant_key AND r.version_id = m.version_id AND r.resource_id = m.resource_id
            JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id
            WHERE v.name = 'Published in 1.x' AND r.resource_type = 'stencil'
            """,
        ).use { rs -> rs.next().let { rs.getInt(1) } }
    }

    private fun repairScript(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
        .resolve("docs/upgrades/2.0.0-alpha.1-repair.sql")

    /** A 1.x database with stencil `header` only drafted and template `invoice` published using it, migrated to the end. */
    private fun upgraded(database: String, withUnpublishedWork: Boolean): Connection {
        val postgres = TestRuntimeLifecycle.postgres()
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
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                    INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', 'acme', 'Letters', 'AUTHORED');
                    INSERT INTO stencils (id, tenant_key, catalog_key, name) VALUES ('header', 'acme', 'letters', 'Header');
                    INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, status)
                        SELECT 1, 'acme', resource_id, '$STENCIL', 'draft' FROM stencils;
                    INSERT INTO document_templates (id, tenant_key, catalog_key, name) VALUES ('invoice', 'acme', 'letters', 'Invoice');
                    INSERT INTO template_variants (id, tenant_key, template_resource_id, title, is_default)
                        SELECT 'main', 'acme', resource_id, 'Main', TRUE FROM document_templates;
                    INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at)
                        SELECT 1, 'acme', resource_id, 'main', '${model("Dear customer")}', 'published', NOW() FROM document_templates;
                    """.trimIndent(),
                )
                if (withUnpublishedWork) {
                    it.execute(
                        """
                        INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status)
                            SELECT 2, 'acme', resource_id, 'main', '${model("Draft wording")}', 'draft' FROM document_templates;
                        """.trimIndent(),
                    )
                }
            }
        }
        flyway(null).migrate()
        return DriverManager.getConnection(url, postgres.username, postgres.password)
    }

    /** The resources (type, key) of letters' draft named [name] (an SQL literal), variants left out. */
    private fun held(connection: Connection, name: String): List<List<String>> = connection.createStatement().use { st ->
        st.executeQuery(
            """
            SELECT r.resource_type, r.resource_key FROM release_resources r
            JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id
            WHERE r.tenant_key = 'acme' AND v.state = 'draft' AND v.name IS NOT DISTINCT FROM $name AND r.parent_id IS NULL
            ORDER BY 1, 2
            """,
        ).use { rs -> buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2))) } }
    }

    private companion object {
        /** The Flyway checksum 2.0.0-alpha.1 recorded for step 9, before it was corrected. */
        const val ALPHA_1_STEP_9 = 1306419192

        const val STENCIL =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["root:children"]}, "t": {"id": "t", "type": "text", "slots": [], "props": {"content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Header"}]}]}}}}, "slots": {"root:children": {"id": "root:children", "name": "children", "nodeId": "root", "children": ["t"]}}}"""

        /** A template model with [text] and an instance of stencil `header`'s draft, kept without a copy. */
        fun model(text: String) = """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["root:children"]}, "t": {"id": "t", "type": "text", "slots": [], "props": {"content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "$text"}]}]}}}, "h": {"id": "h", "type": "stencil", "slots": [], "props": {"stencilId": "header", "version": 1, "isDraft": true}}}, "slots": {"root:children": {"id": "root:children", "name": "children", "nodeId": "root", "children": ["t", "h"]}}}"""
    }
}
