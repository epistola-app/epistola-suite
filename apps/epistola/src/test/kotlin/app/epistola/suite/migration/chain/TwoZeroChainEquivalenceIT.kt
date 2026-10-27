// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.chain

import app.epistola.suite.migration.DataPreservationMigrationIT
import app.epistola.suite.migration.MigrationLauncher
import app.epistola.suite.migration.OneXUpgradeScenariosIT
import app.epistola.suite.migration.preflight.UpgradePreflights
import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 2.0 migration chain converts a 1.x database exactly as the chain it replaced did.
 *
 * The 2.0 chain was written again from scratch, from the last 1.x schema straight to the 2.0 one. What
 * the chain before it left — its schema and every row it made of a rich 1.x seed — is kept as a golden
 * snapshot (`migration-chain/`), recorded from that chain. This migrates the same seed through the
 * chain on the classpath and holds it to the snapshot: the schema statement for statement, the data
 * row for row (generated ids and the time of the run replaced by labels, [ChainSnapshot]). Deliberate
 * differences, such as the indexes the rewrite adds, are listed in `migration-chain/intended.txt`.
 *
 * The seed is the RC1 tenant of [DataPreservationMigrationIT], migrated through 1.x, the 1.x
 * scenarios of [OneXUpgradeScenariosIT], and [WIDE_SEED], a 1.x tenant reaching what those leave out. To record the snapshot again from a chain (only ever the old
 * one, from history), set `EPISTOLA_MIGRATION_CHAIN_RECORD` to this module's `src/test/resources`.
 */
@Tag("integration")
class TwoZeroChainEquivalenceIT {

    @Test
    fun `the 2_0 chain converts a 1_x database as the chain it replaced did`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "two_zero_chain_equivalence"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$database\"")
                it.execute("CREATE DATABASE \"$database\"")
            }
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        migrate(url, postgres.username, postgres.password, "spring.flyway.target=${DataPreservationMigrationIT.RC1_LAST_MIGRATION}")
        connect(url, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute(DataPreservationMigrationIT.RC1_SEED) } }
        migrate(
            url,
            postgres.username,
            postgres.password,
            "spring.flyway.target=$LAST_1X",
            "epistola.migration.preflight.skip=${UpgradePreflights.checks.joinToString(",") { it.id }}",
        )
        connect(url, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute(OneXUpgradeScenariosIT.seed(PARTNER_SOURCE)) } }
        connect(url, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute(WIDE_SEED) } }
        migrate(url, postgres.username, postgres.password)

        val schema = ChainSnapshot.schema(postgres, database)
        val data = connect(url, postgres.username, postgres.password).use { ChainSnapshot.data(it) }
        val ambiguous = connect(url, postgres.username, postgres.password).use { ChainSnapshot.ambiguousRows(it) }

        System.getenv("EPISTOLA_MIGRATION_CHAIN_RECORD")?.let { resources ->
            val dir = Path.of(resources, "migration-chain")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("schema.sql"), schema.joinToString(";\n\n", postfix = ";\n"))
            Files.writeString(dir.resolve("data.txt"), data.joinToString("\n", postfix = "\n"))
            return
        }

        // What this chain made, beside the build, to diff against the snapshot when they differ.
        Path.of("build", "migration-chain").also { dir ->
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("schema.sql"), schema.joinToString(";\n\n", postfix = ";\n"))
            Files.writeString(dir.resolve("data.txt"), data.joinToString("\n", postfix = "\n"))
        }

        val intended = Intended.load()
        val goldenSchema = ChainSnapshot.statements(resource("schema.sql")).map { it.trim() }.filter { it.isNotEmpty() }.map(ChainSnapshot::monthless).sorted()
        assertThat(schema.toSet() - goldenSchema.toSet()).`as`("statements the chain adds beyond the old one's schema").containsExactlyInAnyOrderElementsOf(intended.added)
        assertThat(goldenSchema.toSet() - schema.toSet()).`as`("statements of the old chain's schema this chain does not make").containsExactlyInAnyOrderElementsOf(intended.removed)

        val goldenData = (resource("data.txt").lines().filter { it.isNotEmpty() } - intended.removedRows.toSet() + intended.addedRows).sorted().map(intended::withoutDroppedColumns)
        assertThat(data.map(intended::withoutDroppedColumns)).`as`("every row the old chain made of the seed, and nothing else").containsExactlyElementsOf(goldenData)
        assertThat(ambiguous).`as`("rows the snapshot cannot tell apart, whose labels could swap between runs").isEmpty()
    }

    private fun migrate(url: String, username: String, password: String, vararg props: String) {
        MigrationLauncher.migrationApplication()
            .run(
                "--spring.datasource.url=$url",
                "--spring.datasource.username=$username",
                "--spring.datasource.password=$password",
                "--epistola.migration.mode=migrate",
                "--spring.flyway.clean-disabled=true",
                *props.map { "--$it" }.toTypedArray(),
            )
            .close()
    }

    private fun connect(url: String, username: String, password: String): Connection = DriverManager.getConnection(url, username, password)

    private fun resource(name: String): String = requireNotNull(javaClass.getResource("/migration-chain/$name")) { "missing migration-chain/$name" }.readText()

    /**
     * The deliberate differences from the old chain: `+ statement` it adds, `- statement` it no longer
     * makes, `+ row line` and `- row line` a row it makes or no longer makes (as `data.txt` writes it),
     * `column table.column` a column whose values are no longer compared because it is gone.
     */
    private class Intended(
        val added: List<String>,
        val removed: List<String>,
        val addedRows: List<String>,
        val removedRows: List<String>,
        private val droppedColumns: Set<String>,
    ) {
        fun withoutDroppedColumns(line: String): String {
            val table = line.substringBefore(": ")
            val dropped = droppedColumns.filter { it.startsWith("$table.") }.map { it.substringAfter('.') }
            if (dropped.isEmpty()) return line
            return dropped.fold(line) { acc, column -> acc.replace(Regex(""""$column":(?:"(?:[^"\\]|\\.)*"|[^,}]*),?"""), "") }.replace(",}", "}")
        }

        companion object {
            fun load(): Intended {
                val text = TwoZeroChainEquivalenceIT::class.java.getResource("/migration-chain/intended.txt")?.readText().orEmpty()
                val entries = text.split(Regex("\n(?=[+\\-] |column |#)")).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                val rows = entries.filter { it.startsWith("+ row ") || it.startsWith("- row ") }
                val statements = entries - rows.toSet()
                return Intended(
                    added = statements.filter { it.startsWith("+ ") }.map { it.removePrefix("+ ").trim().removeSuffix(";") },
                    removed = statements.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim().removeSuffix(";") },
                    addedRows = rows.filter { it.startsWith("+ ") }.map { it.removePrefix("+ row ").trim() },
                    removedRows = rows.filter { it.startsWith("- ") }.map { it.removePrefix("- row ").trim() },
                    droppedColumns = entries.filter { it.startsWith("column ") }.map { it.removePrefix("column ").trim() }.toSet(),
                )
            }
        }
    }

    private companion object {
        const val LAST_1X = "20260923150918"

        private const val TEXT = """{"id": "t", "type": "text", "props": {"content": "%s"}}"""
        private val MODEL_V1 = model("hello")
        private val MODEL_V2 = model("draft")
        private val STENCIL_V1 = model("sign here")
        private val STENCIL_V2 = model("sign below")

        private fun model(text: String) = """{"modelVersion": 1, "root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["s"]}, "t": ${TEXT.format(text)}, "seal": {"id": "seal", "type": "image", "props": {"assetId": "00000000-0000-0000-0000-000000000501"}}}, "slots": {"s": {"id": "s", "nodeId": "root", "name": "children", "children": ["t", "seal"]}}, "themeRef": {"type": "override", "themeId": "house"}}"""

        /** The source a 1.x tenant subscribed to; never fetched here, only stored by the seed. */
        const val PARTNER_SOURCE = "file:///nonexistent/partner/catalog.json"

        /**
         * A 1.x tenant reaching what the other seeds leave out: catalog metadata of wire v7, an Exchange
         * connection and binding, a production environment, a house theme with presets, a code list from
         * a URL with a failed refresh, an attribute bound to another catalog's list, a stencil with a draft
         * and one never published, a template whose contract and one variant have drafts, a variant with a
         * description, a template never published, a sensitive image kept apart, a font face whose bytes
         * are still in the content store, a document whose blob is too, a generation request, its document
         * and result, a quality finding with its ignore, and a load-test run.
         */
        val WIDE_SEED = """
            INSERT INTO tenants (id, name) VALUES ('wide', 'Wide');
            INSERT INTO catalogs (id, tenant_key, name, type, catalog_metadata) VALUES
                ('docs', 'wide', 'Docs', 'AUTHORED', '{"presentation": {"iconAssetSlug": "seal", "imageAssetSlugs": ["seal"]}, "attributes": [{"catalog": "lists", "key": "tone", "value": "formal"}]}'),
                ('lists', 'wide', 'Lists', 'AUTHORED', '{}'),
                ('pub', 'wide', 'Pub', 'AUTHORED', '{}');
            INSERT INTO catalogs (id, tenant_key, name, type, source_url, installed_release_version) VALUES
                ('feed', 'wide', 'Feed', 'SUBSCRIBED', 'exchange:partner/feed', '2.0.0');
            INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace) VALUES ('wide', 'pub', 'wide-pub');
            INSERT INTO exchange_tenant_connections (tenant_key, issuer, base_url, authorization_request_endpoint, token_endpoint, namespaces, status, updated_at) VALUES
                ('wide', 'https://exchange.example', 'https://exchange.example', 'https://exchange.example/a', 'https://exchange.example/t', ARRAY['wide-pub', 'Not a slug'], 'ACTIVE', TIMESTAMPTZ '2026-10-01 09:00:00+00');
            INSERT INTO environments (id, tenant_key, name) VALUES ('prd', 'wide', 'Prd'), ('acc', 'wide', 'Productie'), ('test', 'wide', 'Test');

            INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles, block_style_presets, spacing_unit) VALUES
                ('house', 'wide', 'docs', 'House', '{"fontFamily": "Inter"}', '{"quote": {"label": "Quote", "styles": {"fontStyle": "italic"}}, "note": {"styles": {}}}', 4.5);
            UPDATE tenants SET default_theme_resource_id = (SELECT resource_id FROM themes WHERE tenant_key = 'wide' AND id = 'house') WHERE id = 'wide';

            INSERT INTO code_lists (slug, tenant_key, catalog_key, display_name, source_type, source_url, last_refreshed_at, last_refresh_error) VALUES
                ('tones', 'wide', 'lists', 'Tones', 'URL', 'https://lists.example/tones.json', TIMESTAMPTZ '2026-09-01 08:00:00+00', 'HTTP 503');
            INSERT INTO code_list_entries (tenant_key, code_list_resource_id, code, label, sort_order, hidden)
            SELECT 'wide', resource_id, c, l, o, h FROM code_lists, (VALUES ('formal', 'Formal', 2, false), ('casual', 'Casual', 1, false), ('old', 'Old', 1, true)) AS x(c, l, o, h)
            WHERE tenant_key = 'wide';
            INSERT INTO variant_attribute_definitions (id, tenant_key, catalog_key, display_name, allowed_values, code_list_resource_id)
            SELECT 'tone', 'wide', 'docs', 'Tone', '[]', resource_id FROM code_lists WHERE tenant_key = 'wide';
            INSERT INTO variant_attribute_definitions (id, tenant_key, catalog_key, display_name, allowed_values) VALUES
                ('size', 'wide', 'docs', 'Size', '["a4", "a5"]');

            INSERT INTO stencils (id, tenant_key, catalog_key, name, tags) VALUES
                ('sig', 'wide', 'docs', 'Signature', '["legal"]'), ('wip', 'wide', 'docs', 'Work in progress', '[]');
            INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, status, published_at)
            SELECT x.id, 'wide', s.resource_id, x.content::jsonb, x.status, x.published
            FROM stencils s, (VALUES
                ('sig', 1, '$STENCIL_V1', 'published', NOW()),
                ('sig', 2, '$STENCIL_V2', 'draft', NULL::timestamptz),
                ('wip', 1, '$STENCIL_V1', 'draft', NULL::timestamptz)) AS x(stencil, id, content, status, published)
            WHERE s.tenant_key = 'wide' AND s.id = x.stencil;

            INSERT INTO document_templates (id, tenant_key, catalog_key, name, pdfa_enabled) VALUES
                ('letter', 'wide', 'docs', 'Letter', TRUE), ('memo', 'wide', 'docs', 'Memo', FALSE);
            INSERT INTO template_variants (id, tenant_key, template_resource_id, title, description, attributes, is_default, created_at)
            SELECT x.id, 'wide', t.resource_id, x.title, x.description, x.attributes::jsonb, x.is_default, x.created_at
            FROM document_templates t, (VALUES
                ('letter', 'default', 'Default', NULL, '{}', TRUE, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
                ('letter', 'formal', 'Formal', 'For courts', '{"lists.tone": "formal", "size": "a4"}', FALSE, TIMESTAMPTZ '2026-01-02 00:00:00+00'),
                ('memo', 'default', 'Default', '', '{}', TRUE, TIMESTAMPTZ '2026-01-01 00:00:00+00')) AS x(template, id, title, description, attributes, is_default, created_at)
            WHERE t.tenant_key = 'wide' AND t.id = x.template;
            INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at)
            SELECT x.id, 'wide', t.resource_id, x.variant, x.model::jsonb, x.status, x.published
            FROM document_templates t, (VALUES
                ('letter', 'default', 1, '$MODEL_V1', 'published', NOW()),
                ('letter', 'default', 2, '$MODEL_V2', 'draft', NULL::timestamptz),
                ('letter', 'formal', 1, '$MODEL_V1', 'published', NOW()),
                ('memo', 'default', 1, '$MODEL_V1', 'draft', NULL::timestamptz)) AS x(template, variant, id, model, status, published)
            WHERE t.tenant_key = 'wide' AND t.id = x.template;
            INSERT INTO contract_versions (id, tenant_key, template_resource_id, data_model, data_examples, status, published_at)
            SELECT x.id, 'wide', t.resource_id, x.model::jsonb, x.examples::jsonb, x.status, x.published
            FROM document_templates t, (VALUES
                (1, '{"type": "object"}', '[{"id": "e1", "name": "Jane Doe", "data": {}}]', 'published', NOW()),
                (2, '{"type": "object", "required": ["name"]}', '[{"id": "e1", "name": "Jane Doe", "data": {}}, {"id": "e2", "name": "Jane Doe", "data": {}}]', 'draft', NULL::timestamptz)) AS x(id, model, examples, status, published)
            WHERE t.tenant_key = 'wide' AND t.id = 'letter';

            INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes, sensitive, content_hash) VALUES
                ('00000000-0000-0000-0000-000000000501', 'wide', 'docs', 'seal.png', 'image/png', 3, true, '${"5".repeat(64)}'),
                ('00000000-0000-0000-0000-000000000502', 'wide', 'docs', 'serif.ttf', 'font/ttf', 3, false, NULL);
            INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes) VALUES
                ('wide', '${"5".repeat(64)}', '\x050505'::bytea, 'image/png', 3);
            INSERT INTO content_store (key, content, content_type, size_bytes) VALUES
                ('assets/wide/00000000-0000-0000-0000-000000000502', '\x0a0b0c'::bytea, 'font/ttf', 3),
                ('documents/wide/00000000-0000-0000-0000-000000000601', '\x25504446'::bytea, 'application/pdf', 4);
            INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind) VALUES ('serif', 'wide', 'docs', 'Serif', 'serif');
            INSERT INTO font_variants (tenant_key, font_resource_id, weight, italic, source, asset_resource_id)
            SELECT 'wide', f.resource_id, 400, false, 'ASSET', a.resource_id
            FROM fonts f JOIN assets a ON a.tenant_key = f.tenant_key AND a.id = '00000000-0000-0000-0000-000000000502'
            WHERE f.tenant_key = 'wide';

            -- In the month the RC1 seed's document is in, whose partitions exist.
            INSERT INTO documents (id, tenant_key, catalog_key, template_key, variant_key, version_key, filename, size_bytes, created_at)
            VALUES ('00000000-0000-0000-0000-000000000601', 'wide', 'docs', 'letter', 'default', 1, 'letter.pdf', 4, TIMESTAMPTZ '2026-06-15 10:00:00+00');
            INSERT INTO document_generation_requests (id, tenant_key, catalog_key, template_key, variant_key, version_key, data, status, created_at)
            VALUES ('00000000-0000-0000-0000-000000000602', 'wide', 'docs', 'letter', 'default', 1, '{}', 'COMPLETED', TIMESTAMPTZ '2026-06-15 10:00:00+00');
            -- Its sequence given: the partitions' own sequences are time-based.
            INSERT INTO generation_results (sequence, partition, request_id, tenant_key, routing_key, status, document_id, completed_at)
            VALUES (1, 1, '00000000-0000-0000-0000-000000000602', 'wide', 'r', 'COMPLETED', '00000000-0000-0000-0000-000000000601', NOW());

            INSERT INTO quality_findings (tenant_key, id, source_id, rule_id, severity, subject_urn, subject_type, ignore_scope_urn, variant_key, message, fingerprint, template_resource_id)
            SELECT 'wide', '00000000-0000-0000-0000-000000000701', 'lint', 'rule', 'WARNING', 'urn:epistola:template:wide/docs/letter/formal', 'VARIANT',
                   'urn:epistola:template:wide/docs/letter', 'formal', 'Check', 'f1', resource_id
            FROM document_templates WHERE tenant_key = 'wide' AND id = 'letter';
            INSERT INTO quality_finding_ignores (tenant_key, ignore_scope_urn, source_id, rule_id, finding_fingerprint, reason, template_resource_id)
            SELECT 'wide', 'urn:epistola:template:wide/docs/letter', 'lint', 'rule', 'f1', 'Known', resource_id
            FROM document_templates WHERE tenant_key = 'wide' AND id = 'letter';
            INSERT INTO load_test_runs (id, tenant_key, variant_key, version_key, target_count, concurrency_level, test_data, status, template_resource_id)
            SELECT '00000000-0000-0000-0000-000000000801', 'wide', 'default', 1, 10, 2, '{}', 'COMPLETED', resource_id
            FROM document_templates WHERE tenant_key = 'wide' AND id = 'letter';
        """.trimIndent()
    }
}
