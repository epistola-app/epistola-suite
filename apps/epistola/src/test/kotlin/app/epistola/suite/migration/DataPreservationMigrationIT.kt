// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards the RC1 promise: **the database is stable and user data survives every
 * migration**. Seeds a representative tenant on the 1.0.0-RC1 baseline schema, then
 * applies every newer migration and asserts each preserved value survives intact —
 * checked row by row, field by field (jsonb compared by value, not bytes).
 *
 * It also guards the flip side — that an **intentional, scoped data migration does exactly
 * what it claims**: retiring the `stencil-parameters` feature toggle (issue #668) must delete
 * that key's orphaned `feature_toggles` rows while leaving unrelated toggle rows untouched.
 *
 * Mechanics: a fresh logical database in the shared Testcontainer is migrated with
 * `spring.flyway.target` pinned to the last RC1 migration, seeded with raw SQL (raw on
 * purpose — commands can only speak the *current* schema, while this fixture must match
 * the frozen RC1 shape, which never changes because released migrations are immutable),
 * then migrated to latest via the exact production migration context. Every migration that
 * lands after RC1 is thereby exercised against RC1-shaped data and fails this test if it
 * drops or mangles a preserved row — or fails to perform (or over-performs) an expected cleanup.
 */
@Tag("integration")
class DataPreservationMigrationIT {

    @Test
    fun `data seeded on the RC1 schema survives migration to the latest schema`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val databaseName = "data_preservation_it"
        adminConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$databaseName\"")
                it.execute("CREATE DATABASE \"$databaseName\"")
            }
        }
        val targetUrl = perDatabaseUrl(postgres.jdbcUrl, postgres.host, postgres.firstMappedPort, databaseName)

        // 1. Migrate the fresh database to the RC1 baseline only.
        runMigration(targetUrl, postgres.username, postgres.password, "spring.flyway.target=$RC1_LAST_MIGRATION")

        // 2. Seed a representative tenant on the RC1-shaped schema.
        connect(targetUrl, postgres.username, postgres.password).use { seed(it) }

        // 3. Apply everything newer than RC1 (the production migration path).
        runMigration(targetUrl, postgres.username, postgres.password)

        // 4. The seeded data must be intact.
        connect(targetUrl, postgres.username, postgres.password).use { verify(it) }
    }

    private fun runMigration(url: String, username: String, password: String, vararg props: String) {
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

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO tenants (id, name, default_locale)
                VALUES ('$TENANT', 'Preservation Tenant', 'nl-NL');

                INSERT INTO catalogs (id, tenant_key, name, type)
                VALUES ('default', '$TENANT', 'Default Catalog', 'AUTHORED');

                INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles)
                VALUES ('brand', '$TENANT', 'default', 'Brand Theme', '$THEME_STYLES'::jsonb);

                INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_catalog_key, theme_key)
                VALUES ('invoice', '$TENANT', 'default', 'Invoice Template', 'default', 'brand');

                INSERT INTO stencils (id, tenant_key, catalog_key, name)
                VALUES ('header', '$TENANT', 'default', 'Header'),
                       ('draft-only', '$TENANT', 'default', 'Draft only');

                INSERT INTO stencil_versions (id, tenant_key, catalog_key, stencil_key, content, status, published_at)
                VALUES (1, '$TENANT', 'default', 'header', '$STENCIL_CONTENT'::jsonb, 'published', NOW()),
                       (2, '$TENANT', 'default', 'header', '$STENCIL_CONTENT'::jsonb, 'draft', NULL),
                       (1, '$TENANT', 'default', 'draft-only', '$STENCIL_CONTENT'::jsonb, 'draft', NULL);

                -- 'legacy' (NULL) and 'blanktitle' (whitespace) were both allowed pre-#631;
                -- the migration must backfill each with its own slug.
                INSERT INTO template_variants (id, tenant_key, catalog_key, template_key, title, is_default)
                VALUES ('main', '$TENANT', 'default', 'invoice', 'Main Variant', true),
                       ('legacy', '$TENANT', 'default', 'invoice', NULL, false),
                       ('blanktitle', '$TENANT', 'default', 'invoice', '   ', false);

                INSERT INTO template_versions (id, tenant_key, catalog_key, template_key, variant_key,
                                               template_model, status, published_at, referenced_paths)
                VALUES (1, '$TENANT', 'default', 'invoice', 'main',
                        '$TEMPLATE_MODEL'::jsonb, 'published', NOW(), '["customer.name","total"]'::jsonb),
                       (2, '$TENANT', 'default', 'invoice', 'main',
                        '$DRAFT_TEMPLATE_MODEL'::jsonb, 'draft', NULL, '["customer.name","total"]'::jsonb);

                INSERT INTO variant_attribute_definitions (id, tenant_key, catalog_key, display_name, allowed_values)
                VALUES ('language', '$TENANT', 'default', 'Language', '["nl","en"]'::jsonb);

                -- A second catalog, so the identity migrations are exercised against a
                -- cross-catalog reference and not only against everything living in 'default'.
                INSERT INTO catalogs (id, tenant_key, name, type)
                VALUES ('shared', '$TENANT', 'Shared Catalog', 'AUTHORED');

                INSERT INTO code_lists (slug, tenant_key, catalog_key, display_name, source_type)
                VALUES ('countries', '$TENANT', 'shared', 'Countries', 'INLINE');

                INSERT INTO code_list_entries (tenant_key, catalog_key, code_list_slug, code, label, sort_order)
                VALUES ('$TENANT', 'shared', 'countries', 'nl', 'Netherlands', 0),
                       ('$TENANT', 'shared', 'countries', 'be', 'Belgium', 1);

                -- Bound across catalogs: the attribute is in 'default', its code list in 'shared'.
                INSERT INTO variant_attribute_definitions
                    (id, tenant_key, catalog_key, display_name, allowed_values, code_list_catalog_key, code_list_slug)
                VALUES ('country', '$TENANT', 'default', 'Country', '[]'::jsonb, 'shared', 'countries');

                INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes)
                VALUES ('00000000-0000-0000-0000-000000000301', '$TENANT', 'default', 'logo.png', 'image/png', 512),
                       ('00000000-0000-0000-0000-000000000302', '$TENANT', 'default', 'brand-regular.ttf', 'font/ttf', 2048);

                INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind)
                VALUES ('brand', '$TENANT', 'default', 'Brand Sans', 'sans');

                -- One face of each source: the ASSET face is the one whose binary pointer the
                -- migration has to re-key, the CLASSPATH face the one it must leave unbound.
                INSERT INTO font_variants
                    (tenant_key, catalog_key, font_slug, weight, italic, source, asset_key, classpath_location, content_hash)
                VALUES ('$TENANT', 'default', 'brand', 400, false, 'ASSET',
                        '00000000-0000-0000-0000-000000000302', NULL, 'abc123'),
                       ('$TENANT', 'default', 'brand', 700, false, 'CLASSPATH',
                        NULL, 'epistola/fonts/brand/brand-Bold.ttf', 'def456');

                INSERT INTO contract_versions (id, tenant_key, catalog_key, template_key, data_model, status, published_at)
                VALUES (1, '$TENANT', 'default', 'invoice',
                        '{"type":"object"}'::jsonb, 'published', NOW());

                -- documents is partitioned like audit_log below; the fixture owns its partition.
                CREATE TABLE documents_202606
                    PARTITION OF documents
                    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

                INSERT INTO documents (id, tenant_key, catalog_key, template_key, variant_key, version_key,
                                       filename, size_bytes, created_at)
                VALUES ('00000000-0000-0000-0000-000000000201', '$TENANT', 'default', 'invoice', 'main', 1,
                        'invoice.pdf', 1024, '2026-06-23 10:00:00+00');

                -- Partitioned like documents; the fixture owns its partition.
                CREATE TABLE document_generation_requests_202606
                    PARTITION OF document_generation_requests
                    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

                INSERT INTO document_generation_requests
                    (id, tenant_key, catalog_key, template_key, variant_key, version_key, data, status, created_at)
                VALUES ('00000000-0000-0000-0000-000000000401', '$TENANT', 'default', 'invoice', 'main', 1,
                        '{}'::jsonb, 'COMPLETED', '2026-06-23 10:00:00+00');

                -- An orphaned toggle row for the retired 'stencil-parameters' feature (deleted by
                -- V20260708110402) plus a control row for a still-live feature that must survive.
                INSERT INTO feature_toggles (tenant_key, feature_key, enabled)
                VALUES ('$TENANT', 'stencil-parameters', true),
                       ('$TENANT', 'support-feedback', false);

                -- audit_log is partitioned, while this migration-only fixture does not start the
                -- partition scheduler. Create the historical partition explicitly so the scoped
                -- consumer-housekeeping cleanup can be exercised against pre-existing rows.
                CREATE TABLE audit_log_202606
                    PARTITION OF audit_log
                    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

                INSERT INTO audit_log
                    (id, occurred_at, tenant_key, action, operation, outcome, instance_id)
                VALUES
                    ('00000000-0000-0000-0000-000000000101', '2026-06-23 10:00:00+00', '$TENANT',
                     'TouchConsumerNode', 'WRITE', 'SUCCESS', 'migration-test'),
                    ('00000000-0000-0000-0000-000000000102', '2026-06-23 10:00:01+00', '$TENANT',
                     'TouchConsumerNode', 'WRITE', 'FAILURE', 'migration-test'),
                    ('00000000-0000-0000-0000-000000000103', '2026-06-23 10:00:02+00', '$TENANT',
                     'AcknowledgeGenerationResults', 'WRITE', 'SUCCESS', 'migration-test'),
                    ('00000000-0000-0000-0000-000000000104', '2026-06-23 10:00:03+00', '$TENANT',
                     'AcknowledgeGenerationResults', 'WRITE', 'FAILURE', 'migration-test'),
                    ('00000000-0000-0000-0000-000000000105', '2026-06-23 10:00:04+00', '$TENANT',
                     'CreateTheme', 'WRITE', 'SUCCESS', 'migration-test');
                """.trimIndent(),
            )
        }
    }

    private fun verify(connection: Connection) {
        connection.createStatement().use { statement ->
            fun one(sql: String): String? = statement.executeQuery(sql).use { rs ->
                assertThat(rs.next()).describedAs("row missing after migration: $sql").isTrue()
                rs.getString(1)
            }

            // Hierarchy rows name the template's identity now, so every lookup resolves the
            // seeded address through document_templates rather than filtering on a copy of it.
            val ofInvoice =
                "template_resource_id = (SELECT resource_id FROM document_templates " +
                    "WHERE tenant_key = '$TENANT' AND catalog_key = 'default' AND id = 'invoice')"

            assertThat(one("SELECT name FROM tenants WHERE id = '$TENANT'"))
                .isEqualTo("Preservation Tenant")
            assertThat(one("SELECT default_locale FROM tenants WHERE id = '$TENANT'"))
                .isEqualTo("nl-NL")
            assertThat(one("SELECT type FROM catalogs WHERE tenant_key = '$TENANT' AND id = 'default'"))
                .isEqualTo("AUTHORED")
            assertThat(one("SELECT document_styles::text FROM themes WHERE tenant_key = '$TENANT' AND id = 'brand'"))
                .isEqualTo(one("SELECT '$THEME_STYLES'::jsonb::text"))
            assertThat(
                one(
                    """
                    SELECT theme.id FROM document_templates template
                    JOIN themes theme ON theme.tenant_key = template.tenant_key
                                     AND theme.resource_id = template.theme_resource_id
                    WHERE template.tenant_key = '$TENANT' AND template.id = 'invoice'
                    """,
                ),
            )
                .describedAs("the template's theme address must have been re-keyed onto that theme's identity")
                .isEqualTo("brand")
            assertThat(
                one(
                    """
                    SELECT list.slug FROM variant_attribute_definitions attribute
                    JOIN code_lists list ON list.tenant_key = attribute.tenant_key
                                        AND list.resource_id = attribute.code_list_resource_id
                    WHERE attribute.tenant_key = '$TENANT' AND attribute.id = 'country'
                    """,
                ),
            )
                .describedAs("a cross-catalog code-list binding must have been re-keyed onto that list's identity")
                .isEqualTo("countries")
            assertThat(
                one(
                    """
                    SELECT count(*)::text FROM code_list_entries entries
                    JOIN code_lists list ON list.tenant_key = entries.tenant_key
                                        AND list.resource_id = entries.code_list_resource_id
                    WHERE list.tenant_key = '$TENANT' AND list.catalog_key = 'shared' AND list.slug = 'countries'
                    """,
                ),
            )
                .describedAs("code-list entries must have followed their list onto its identity")
                .isEqualTo("2")
            assertThat(
                one(
                    """
                    SELECT binary_asset.name
                    FROM font_variants faces
                    JOIN fonts family ON family.tenant_key = faces.tenant_key
                                     AND family.resource_id = faces.font_resource_id
                    JOIN assets binary_asset ON binary_asset.tenant_key = faces.tenant_key
                                            AND binary_asset.resource_id = faces.asset_resource_id
                    WHERE family.tenant_key = '$TENANT' AND family.slug = 'brand' AND faces.weight = 400
                    """,
                ),
            )
                .describedAs("an ASSET face must reach its family and its binary through both identities")
                .isEqualTo("brand-regular.ttf")
            assertThat(
                one(
                    """
                    SELECT faces.classpath_location
                    FROM font_variants faces
                    JOIN fonts family ON family.tenant_key = faces.tenant_key
                                     AND family.resource_id = faces.font_resource_id
                    WHERE family.tenant_key = '$TENANT' AND family.slug = 'brand' AND faces.weight = 700
                      AND faces.asset_resource_id IS NULL
                    """,
                ),
            )
                .describedAs("a CLASSPATH face has no binary, and the backfill must not invent one")
                .isEqualTo("epistola/fonts/brand/brand-Bold.ttf")
            assertThat(one("SELECT status FROM contract_versions WHERE tenant_key = '$TENANT' AND $ofInvoice AND id = 1"))
                .isEqualTo("published")
            assertThat(
                one(
                    """
                    SELECT count(*)::text FROM stencil_versions versions
                    JOIN stencils stencil ON stencil.tenant_key = versions.tenant_key
                                         AND stencil.resource_id = versions.stencil_resource_id
                    WHERE stencil.tenant_key = '$TENANT' AND stencil.id = 'header'
                    """,
                ),
            )
                .describedAs("both versions of a stencil must have followed it onto its identity")
                .isEqualTo("2")
            assertThat(
                one(
                    "SELECT status FROM document_generation_requests " +
                        "WHERE id = '00000000-0000-0000-0000-000000000401'",
                ),
            )
                .describedAs("generation history survives the template re-key, keeping its recorded address")
                .isEqualTo("COMPLETED")
            assertThat(one("SELECT title FROM template_variants WHERE tenant_key = '$TENANT' AND $ofInvoice AND id = 'main'"))
                .isEqualTo("Main Variant")
            assertThat(one("SELECT title FROM template_variants WHERE tenant_key = '$TENANT' AND $ofInvoice AND id = 'legacy'"))
                .describedAs("legacy NULL-title variant must be backfilled with its own slug")
                .isEqualTo("legacy")
            assertThat(one("SELECT title FROM template_variants WHERE tenant_key = '$TENANT' AND $ofInvoice AND id = 'blanktitle'"))
                .describedAs("blank-title variant must be backfilled with its own slug")
                .isEqualTo("blanktitle")
            assertThat(one("SELECT template_model::text FROM template_versions WHERE tenant_key = '$TENANT' AND $ofInvoice AND variant_key = 'main' AND id = 1"))
                .isEqualTo(one("SELECT '$TEMPLATE_MODEL'::jsonb::text"))
            assertThat(one("SELECT status FROM template_versions WHERE tenant_key = '$TENANT' AND $ofInvoice AND variant_key = 'main' AND id = 1"))
                .isEqualTo("published")
            assertThat(one("SELECT referenced_paths::text FROM template_versions WHERE tenant_key = '$TENANT' AND $ofInvoice AND variant_key = 'main' AND id = 1"))
                .isEqualTo(one("""SELECT '["customer.name","total"]'::jsonb::text"""))
            assertThat(one("SELECT template_model::text FROM template_versions WHERE tenant_key = '$TENANT' AND $ofInvoice AND variant_key = 'main' AND id = 2"))
                .describedAs("draft stencil references must retain their published base and gain exact draft provenance")
                .isEqualTo(one("SELECT '$MIGRATED_DRAFT_TEMPLATE_MODEL'::jsonb::text"))

            // Relocation re-keyed attributes onto their identity (V20260905090100) and dropped the
            // generation-history foreign keys into the template hierarchy (V20260905090200).
            assertThat(one("SELECT display_name FROM variant_attribute_definitions WHERE tenant_key = '$TENANT' AND catalog_key = 'default' AND id = 'language'"))
                .describedAs("attribute must survive its primary key moving onto resource_id")
                .isEqualTo("Language")
            assertThat(one("SELECT count(*) FROM catalog_resources WHERE tenant_key = '$TENANT' AND resource_type = 'attribute' AND catalog_key = 'default' AND resource_key = 'language'"))
                .describedAs("RC1-era attribute must be registered with a stable identity")
                .isEqualTo("1")
            assertThat(one("SELECT filename FROM documents WHERE tenant_key = '$TENANT' AND catalog_key = 'default' AND template_key = 'invoice'"))
                .describedAs("generation history must survive its template foreign keys being dropped")
                .isEqualTo("invoice.pdf")
            assertThat(one("SELECT count(*) FROM documents WHERE tenant_key = '$TENANT' AND template_key = 'invoice' AND template_resource_id IS NULL"))
                .describedAs("history keeps its recorded address and gains the identity, so a later rename stays resolvable")
                .isEqualTo("0")

            // Intentional scoped cleanup (V20260708110402, issue #668): the retired
            // stencil-parameters toggle's orphaned rows are gone, unrelated toggles survive.
            assertThat(one("SELECT count(*) FROM feature_toggles WHERE tenant_key = '$TENANT' AND feature_key = 'stencil-parameters'"))
                .describedAs("retired stencil-parameters toggle rows must be deleted")
                .isEqualTo("0")
            assertThat(one("SELECT count(*) FROM feature_toggles WHERE tenant_key = '$TENANT' AND feature_key = 'support-feedback'"))
                .describedAs("unrelated feature-toggle rows must survive the scoped delete")
                .isEqualTo("1")

            // Consumer transport housekeeping is intentionally removed from historical audit
            // partitions, including failures; unrelated audit history remains untouched.
            assertThat(one("SELECT count(*) FROM audit_log WHERE action = 'TouchConsumerNode'"))
                .describedAs("historical consumer heartbeat audit rows must be deleted")
                .isEqualTo("0")
            assertThat(one("SELECT count(*) FROM audit_log WHERE action = 'AcknowledgeGenerationResults'"))
                .describedAs("historical consumer acknowledgement audit rows must be deleted")
                .isEqualTo("0")
            assertThat(one("SELECT count(*) FROM audit_log WHERE action = 'CreateTheme'"))
                .describedAs("unrelated audit rows must survive the scoped delete")
                .isEqualTo("1")
        }
    }

    private fun adminConnection(url: String, username: String, password: String): Connection = DriverManager.getConnection(url, username, password)

    private fun connect(url: String, username: String, password: String): Connection = DriverManager.getConnection(url, username, password)

    private fun perDatabaseUrl(containerUrl: String, host: String, port: Int, databaseName: String): String {
        val queryParams = containerUrl.substringAfter('?', "")
        val base = "jdbc:postgresql://$host:$port/$databaseName"
        return if (queryParams.isEmpty()) base else "$base?$queryParams"
    }

    companion object {
        /**
         * The last migration shipped in 1.0.0-RC1 — the frozen baseline this fixture's
         * SQL is written against. Do NOT bump this when adding migrations; the whole
         * point is that data seeded on this schema must survive everything after it.
         */
        private const val RC1_LAST_MIGRATION = "20260622102813"

        private const val TENANT = "preserve-tenant"
        private const val THEME_STYLES = """{"fontFamily": "serif", "fontSize": 11}"""
        private const val TEMPLATE_MODEL =
            """{"rootNodeId": "root-1", "nodes": {"root-1": {"type": "page"}}, "marker": "rc1-preservation"}"""
        private const val STENCIL_CONTENT =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}}, "slots": {}}"""
        private const val DRAFT_TEMPLATE_MODEL =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}, "linked": {"id": "linked", "type": "stencil", "slots": [], "props": {"stencilId": "header", "version": 1, "isDraft": true}}, "new": {"id": "new", "type": "stencil", "slots": [], "props": {"stencilId": "draft-only", "version": 1, "isDraft": true}}}, "slots": {}, "marker": "catalog-v5"}"""
        private const val MIGRATED_DRAFT_TEMPLATE_MODEL =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}, "linked": {"id": "linked", "type": "stencil", "slots": [], "props": {"stencilId": "header", "version": 1, "draftVersion": 2}}, "new": {"id": "new", "type": "stencil", "slots": [], "props": {"stencilId": "draft-only", "draftVersion": 1}}}, "slots": {}, "marker": "catalog-v5"}"""
    }
}
