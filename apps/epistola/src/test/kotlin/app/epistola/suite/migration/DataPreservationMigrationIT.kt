// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.migration.preflight.UpgradePreflightOutcome
import app.epistola.suite.migration.preflight.UpgradePreflights
import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.postgresql.PostgreSQLContainer
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
 *
 * It reaches 2.0 the way a supported installation does: 1.x migrates the RC1 data up to the last
 * 1.x release (1.5.0), then the 2.0 step runs its preflight on that shape and migrates the rest. A
 * direct upgrade is promised from a supported version only (SUPPORT_POLICY.md), and every one of
 * them (1.2.0 onwards) has the shape the 1.x-to-2.0 checks read; RC1's has not (no
 * `template_resource_id`), so the checks refuse an RC1 database rather than read it.
 *
 * It runs once per supported PostgreSQL major version, whichever one the rest of the suite is on:
 * an installation upgrades on the server it already has, so a migration that only works on a newer
 * one fails that installation mid-upgrade. The version the shared container runs reuses it; the
 * other gets a server of its own.
 */
@Tag("integration")
class DataPreservationMigrationIT {

    @ParameterizedTest(name = "on PostgreSQL {0}")
    @ValueSource(ints = [17, 18])
    fun `data seeded on the RC1 schema survives migration to the latest schema`(postgresVersion: Int) {
        if (postgresVersion == TestRuntimeLifecycle.postgresMajorVersion) {
            migrateSeedAndVerify(TestRuntimeLifecycle.postgres(), postgresVersion)
        } else {
            TestRuntimeLifecycle.newPostgresContainer(postgresVersion).use { postgres ->
                postgres.start()
                migrateSeedAndVerify(postgres, postgresVersion)
            }
        }
    }

    private fun migrateSeedAndVerify(postgres: PostgreSQLContainer, postgresVersion: Int) {
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

        // 3. Up to the last 1.x release, as 1.x migrated it. 1.x had no 2.0 preflight, so the 2.0
        //    launcher skips its checks for this step only.
        runMigration(
            targetUrl,
            postgres.username,
            postgres.password,
            "spring.flyway.target=$LAST_1X_MIGRATION",
            "epistola.migration.preflight.skip=${UpgradePreflights.checks.joinToString(",") { it.id }}",
        )

        // 4. The 1.x-to-2.0 checks read the seeded data at that shape, and nothing they read stops the
        //    upgrade: every check ran (none is left out as not applying) and none found a problem.
        connect(targetUrl, postgres.username, postgres.password).use { connection ->
            val preflight = UpgradePreflights.run(connection)
            assertThat(preflight.outcomes).allSatisfy { assertThat(it).isInstanceOf(UpgradePreflightOutcome.Ran::class.java) }
            assertThat(preflight.passed).describedAs(preflight.describe()).isTrue()
        }

        // 5. Apply everything newer than 1.x (the production migration path, preflight included).
        runMigration(targetUrl, postgres.username, postgres.password)

        // 6. The seeded data must be intact, on the server this run claims to be exercising.
        connect(targetUrl, postgres.username, postgres.password).use { connection ->
            assertThat(connection.metaData.databaseMajorVersion)
                .describedAs("PostgreSQL major version the migrations ran on")
                .isEqualTo(postgresVersion)
            verify(connection)
        }
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

                -- Bytes the 1.x content backfill (#738) had not moved yet: the 2.0 upgrade moves them.
                INSERT INTO content_store (key, content, content_type, size_bytes) VALUES
                    ('assets/$TENANT/00000000-0000-0000-0000-000000000301', '\x89504e47'::bytea, 'image/png', 4),
                    ('documents/$TENANT/00000000-0000-0000-0000-000000000201', '\x25504446'::bytea, 'application/pdf', 4);

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
            // A catalog is named by its key until 2.0 identities it (V20261018200100): resources
            // name the catalog's opaque catalog_id, so lookups resolve the seeded key to it.
            fun catalogId(key: String) = "(SELECT catalog_id FROM catalogs WHERE tenant_key = '$TENANT' AND catalog_key = '$key')"
            val ofInvoice =
                "template_resource_id = (SELECT resource_id FROM document_templates " +
                    "WHERE tenant_key = '$TENANT' AND catalog_id = ${catalogId("default")} AND id = 'invoice')"

            assertThat(one("SELECT name FROM tenants WHERE id = '$TENANT'"))
                .isEqualTo("Preservation Tenant")
            assertThat(one("SELECT default_locale FROM tenants WHERE id = '$TENANT'"))
                .isEqualTo("nl-NL")
            assertThat(one("SELECT type FROM catalogs WHERE tenant_key = '$TENANT' AND catalog_key = 'default'"))
                .isEqualTo("AUTHORED")
            // Themes are stored as JSON (ADR 0033, V20261024114440): the theme's content says what its
            // 1.x row said, and its draft row keeps the row's bookkeeping.
            assertThat(one("SELECT document_styles::text FROM current_themes WHERE tenant_key = '$TENANT' AND id = 'brand'"))
                .isEqualTo(one("SELECT '$THEME_STYLES'::jsonb::text"))
            assertThat(one("SELECT payload::text FROM current_themes WHERE tenant_key = '$TENANT' AND id = 'brand'"))
                .describedAs("the 1.x theme, its wire form as the content's resource")
                .isEqualTo(
                    one(
                        """SELECT '{"resource": {"key": "brand", "name": "Brand Theme", "type": "theme", "description": null, "spacingUnit": null,
                        "pageSettings": null, "documentStyles": $THEME_STYLES, "blockStylePresets": null}}'::jsonb::text""",
                    ),
                )
            assertThat(one("SELECT revision || '/' || (created_by IS NULL) || '/' || (created_at <= updated_at) FROM current_themes WHERE tenant_key = '$TENANT' AND id = 'brand'"))
                .describedAs("its revision and authorship, as its 1.x row had them")
                .isEqualTo("1/true/true")
            assertThat(one("SELECT CAST(to_regclass('themes') IS NULL AS TEXT)")).describedAs("the themes table is gone").isEqualTo("true")
            assertThat(
                one(
                    """
                    SELECT theme.id FROM document_templates template
                    JOIN current_themes theme ON theme.tenant_key = template.tenant_key
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
                    SELECT list.id FROM current_attributes attribute
                    JOIN current_code_lists list ON list.tenant_key = attribute.tenant_key
                                        AND list.catalog_id = attribute.code_list_catalog_id AND list.id = attribute.code_list_key
                    WHERE attribute.tenant_key = '$TENANT' AND attribute.id = 'country'
                    """,
                ),
            )
                .describedAs("a cross-catalog code-list binding names that list, by alias and key (ADR 0033)")
                .isEqualTo("countries")
            assertThat(
                one(
                    """
                    SELECT count(*)::text FROM current_code_lists list
                    CROSS JOIN LATERAL jsonb_array_elements(list.entries) entries
                    WHERE list.tenant_key = '$TENANT' AND list.catalog_id = ${catalogId("shared")} AND list.id = 'countries'
                    """,
                ),
            )
                .describedAs("code-list entries must have followed their list onto its identity")
                .isEqualTo("2")
            // Code lists are stored as JSON (ADR 0033, V20261024174143): the 1.x list as its wire form.
            assertThat(one("SELECT payload::text FROM current_code_lists WHERE tenant_key = '$TENANT' AND id = 'countries'"))
                .describedAs("the 1.x code list, as its wire form; an INLINE list has no source")
                .isEqualTo(
                    one(
                        """SELECT '{"resource": {"key": "countries", "name": "Countries", "type": "codeList", "entries": [
                        {"code": "nl", "label": "Netherlands", "hidden": false, "sortOrder": 0},
                        {"code": "be", "label": "Belgium", "hidden": false, "sortOrder": 1}], "description": null}}'::jsonb::text""",
                    ),
                )
            assertThat(one("SELECT (payload -> 'resource' -> 'codeListBinding')::text FROM current_attributes WHERE tenant_key = '$TENANT' AND id = 'country'"))
                .describedAs("the 1.x binding, as the wire form names it")
                .isEqualTo(one("""SELECT '{"key": "countries", "catalogKey": "shared"}'::jsonb::text"""))
            assertThat(one("SELECT CAST(to_regclass('variant_attribute_definitions') IS NULL AS TEXT)")).describedAs("the attribute table is gone").isEqualTo("true")
            assertThat(one("SELECT CAST(to_regclass('stencils') IS NULL AS TEXT)")).describedAs("the stencil table is gone").isEqualTo("true")
            assertThat(one("SELECT CAST(to_regclass('code_lists') IS NULL AND to_regclass('code_list_entries') IS NULL AS TEXT)"))
                .describedAs("the code list tables are gone").isEqualTo("true")
            // A font is stored as JSON (ADR 0033, step 5): each face by its hash, its file the font's binary.
            assertThat(
                one(
                    """
                    SELECT string_agg((face ->> 'weight') || '/' || (face ->> 'contentHash'), ',' ORDER BY face ->> 'weight')
                    FROM current_fonts f CROSS JOIN LATERAL jsonb_array_elements(f.variants) face
                    WHERE f.tenant_key = '$TENANT' AND f.slug = 'brand'
                    """,
                ),
            )
                .describedAs("both faces, uploaded and bundled, by the hash 1.x recorded")
                .isEqualTo("400/abc123,700/def456")
            assertThat(one("SELECT CAST(count(*) AS TEXT) FROM current_images WHERE tenant_key = '$TENANT' AND media_type NOT LIKE 'image/%'"))
                .describedAs("a face's file whose bytes 1.x had already lost is no image: left behind, logged, nothing refused")
                .isEqualTo("0")
            assertThat(one("SELECT content_hash FROM current_images WHERE tenant_key = '$TENANT' AND name = 'logo.png'"))
                .describedAs("an image whose bytes 1.x had not moved names them, moved in the migration step")
                .isEqualTo("0f4636c78f65d3639ece5a064b5ae753e3408614a14fb18ab4d7540d2c248543")
            assertThat(one("SELECT encode(content, 'hex') FROM document_content WHERE key = 'documents/$TENANT/00000000-0000-0000-0000-000000000201'"))
                .describedAs("a document whose bytes 1.x had not moved has them in document_content").isEqualTo("25504446")
            assertThat(one("SELECT CAST(to_regclass('assets') IS NULL AND to_regclass('content_store') IS NULL AS TEXT)")).describedAs("the asset and legacy content tables are gone").isEqualTo("true")
            assertThat(one("SELECT CAST(to_regclass('fonts') IS NULL AND to_regclass('font_variants') IS NULL AS TEXT)")).describedAs("the font tables are gone").isEqualTo("true")
            // 2.0 drops the 1.x versions (V20261018201400), keeping what the first release is cut from
            // and a summary of the rest.
            fun history(list: String, filter: String) = one(
                "SELECT jsonb_agg(v ORDER BY v->>'version')::text FROM app_metadata, jsonb_array_elements(value->'$list') v " +
                    "WHERE key = 'upgrade-1x-history' AND v->>'tenant' = '$TENANT' AND $filter",
            )
            assertThat(history("contractVersions", "v->>'template' = 'invoice'")).contains("\"status\": \"published\"")
            assertThat(history("stencilVersions", "v->>'stencil' = 'header'"))
                .describedAs("both versions of a stencil are in the summary, under the stencil's key")
                .contains("\"version\": 1").contains("\"version\": 2")
            assertThat(
                one(
                    """
                    SELECT count(*)::text FROM catalog_upgrade_published p
                    JOIN current_stencils stencil ON stencil.tenant_key = p.tenant_key AND stencil.resource_id = p.resource_id
                    WHERE stencil.tenant_key = '$TENANT' AND stencil.id IN ('header', 'draft-only') AND p.kind = 'stencil'
                    """,
                ),
            )
                .describedAs("the published stencil is kept for the first release; the draft-only one has nothing published")
                .isEqualTo("1")
            assertThat(one("SELECT count(*)::text FROM information_schema.tables WHERE table_name IN ('template_versions', 'contract_versions', 'stencil_versions', 'environment_activations')"))
                .isEqualTo("0")
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
            assertThat(
                one(
                    """
                    SELECT (p.payload->'templateModel')::text FROM catalog_upgrade_published p
                    JOIN document_templates dt ON dt.tenant_key = p.tenant_key AND dt.resource_id = p.resource_id
                    WHERE dt.tenant_key = '$TENANT' AND dt.id = 'invoice' AND p.kind = 'variant' AND p.part = 'main'
                    """,
                ),
            )
                .describedAs("the variant's last published model is what its first release is cut from")
                .isEqualTo(one("SELECT '$TEMPLATE_MODEL'::jsonb::text"))
            assertThat(history("templateVersions", "v->>'template' = 'invoice' AND v->>'variant' = 'main'"))
                .contains("\"status\": \"published\"").contains("\"status\": \"draft\"")

            // Relocation re-keyed attributes onto their identity (V20260905090100) and dropped the
            // generation-history foreign keys into the template hierarchy (V20260905090200).
            assertThat(one("SELECT display_name FROM current_attributes WHERE tenant_key = '$TENANT' AND catalog_id = ${catalogId("default")} AND id = 'language'"))
                .describedAs("attribute must survive its primary key moving onto resource_id")
                .isEqualTo("Language")
            assertThat(one("SELECT count(*) FROM catalog_resources WHERE tenant_key = '$TENANT' AND resource_type = 'attribute' AND catalog_id = ${catalogId("default")} AND resource_key = 'language'"))
                .describedAs("RC1-era attribute must be registered with a stable identity")
                .isEqualTo("1")
            assertThat(one("SELECT (count(*) > 0 AND bool_and(substring(resource_id::text, 15, 1) = '7'))::text FROM catalog_resources WHERE tenant_key = '$TENANT'"))
                .describedAs("every backfilled identity must be a time-ordered UUIDv7, on every supported server")
                .isEqualTo("true")
            assertThat(one("SELECT filename FROM documents WHERE tenant_key = '$TENANT' AND catalog_key = 'default' AND template_key = 'invoice'"))
                .describedAs("generation history must survive its template foreign keys being dropped")
                .isEqualTo("invoice.pdf")
            assertThat(one("SELECT count(*) FROM documents WHERE tenant_key = '$TENANT' AND template_key = 'invoice' AND template_resource_id IS NULL"))
                .describedAs("generation history is filled forward, never backfilled: rewriting every partition at upgrade time is the one cost this migration refuses to pay")
                .isEqualTo("1")
            assertThat(one("SELECT count(*) FROM template_variants WHERE tenant_key = '$TENANT' AND template_resource_id IS NULL"))
                .describedAs("the hierarchy IS backfilled -- it is bounded, and its foreign keys require it")
                .isEqualTo("0")

            // 2.0 names every catalog (V20261018200100): an authored catalog on the default
            // registry, in the tenant's namespace.
            assertThat(one("SELECT registry || '~' || namespace FROM catalogs WHERE tenant_key = '$TENANT' AND catalog_key = 'shared'"))
                .isEqualTo("exchange.epistola.app~$TENANT")

            // 2.0 keeps one working copy (V20261018200400, V20261018200600): what each author was
            // working on, the draft where there is one, else the published version.
            assertThat(one("SELECT template_model::text FROM template_variants WHERE tenant_key = '$TENANT' AND $ofInvoice AND id = 'main'"))
                .describedAs("a variant's working model is its draft")
                .isEqualTo(one("SELECT '$MIGRATED_DRAFT_TEMPLATE_MODEL'::jsonb::text"))
            assertThat(one("SELECT referenced_paths::text FROM template_variants WHERE tenant_key = '$TENANT' AND $ofInvoice AND id = 'main'"))
                .isEqualTo(one("""SELECT '["customer.name","total"]'::jsonb::text"""))
            assertThat(one("SELECT template_model->>'root' FROM template_variants WHERE tenant_key = '$TENANT' AND $ofInvoice AND id = 'legacy'"))
                .describedAs("a variant that never had a version starts from the empty document")
                .isEqualTo("root")
            assertThat(one("SELECT data_model::text FROM document_templates WHERE tenant_key = '$TENANT' AND catalog_id = ${catalogId("default")} AND id = 'invoice'"))
                .describedAs("the template's data contract is its published one")
                .isEqualTo(one("""SELECT '{"type":"object"}'::jsonb::text"""))
            assertThat(one("SELECT content::text FROM current_stencils WHERE tenant_key = '$TENANT' AND id = 'header'"))
                .describedAs("a stencil's working copy is its draft")
                .isEqualTo(one("SELECT '$STENCIL_CONTENT'::jsonb::text"))

            // What was publishable is ready; a variant or stencil with unfinished draft work is not.
            fun ready(resource: String, part: String) = one(
                """
                SELECT (m.digest IS NOT DISTINCT FROM d.digest)::text
                FROM resource_content_digests d
                LEFT JOIN resource_ready_marks m ON m.tenant_key = d.tenant_key AND m.resource_id = d.resource_id AND m.part = d.part
                WHERE d.tenant_key = '$TENANT' AND d.resource_id = ($resource) AND d.part = '$part'
                """,
            )
            val invoice = "SELECT resource_id FROM document_templates WHERE tenant_key = '$TENANT' AND id = 'invoice'"
            assertThat(ready(invoice, "main")).describedAs("a variant with a draft that differs from its published version").isEqualTo("false")
            assertThat(ready(invoice, "legacy")).describedAs("a variant without a draft").isEqualTo("true")
            assertThat(ready("SELECT resource_id FROM current_stencils WHERE tenant_key = '$TENANT' AND id = 'header'", ""))
                .describedAs("a stencil with a draft").isEqualTo("false")
            assertThat(ready("SELECT resource_id FROM current_themes WHERE tenant_key = '$TENANT' AND id = 'brand'", ""))
                .describedAs("a resource that had no drafts").isEqualTo("true")

            // Every authored catalog has the draft it works in, its next version, which has no number
            // until it is released (V20261018200900).
            assertThat(one("SELECT string_agg(c.catalog_key || '@' || CASE WHEN d.is_next THEN 'next' ELSE d.name END || COALESCE('#' || d.version, ''), ',' ORDER BY c.catalog_key) FROM catalog_versions d JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id WHERE d.tenant_key = '$TENANT' AND d.kind = 'draft'"))
                .isEqualTo("default@next,shared@next")

            // Every authored 1.x catalog is queued for its declarations and first release (V20261018201400).
            assertThat(one("SELECT string_agg(c.catalog_key, ',' ORDER BY c.catalog_key) FROM catalog_upgrade_queue q JOIN catalogs c ON c.tenant_key = q.tenant_key AND c.catalog_id = q.catalog_id WHERE q.tenant_key = '$TENANT'"))
                .isEqualTo("default,shared")

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

        /** The last migration 1.x shipped (1.4.0 and 1.5.0 both end on it): where a 2.0 upgrade starts. */
        private const val LAST_1X_MIGRATION = "20260923150918"

        private const val TENANT = "preserve-tenant"
        private const val THEME_STYLES = """{"fontFamily": "serif", "fontSize": 11}"""
        private const val TEMPLATE_MODEL =
            """{"rootNodeId": "root-1", "nodes": {"root-1": {"type": "page"}}, "marker": "rc1-preservation"}"""
        private const val STENCIL_CONTENT =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}}, "slots": {}}"""
        private const val DRAFT_TEMPLATE_MODEL =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}, "linked": {"id": "linked", "type": "stencil", "slots": [], "props": {"stencilId": "header", "version": 1, "isDraft": true}}, "new": {"id": "new", "type": "stencil", "slots": [], "props": {"stencilId": "draft-only", "version": 1, "isDraft": true}}}, "slots": {}, "marker": "catalog-v5"}"""

        /**
         * The draft as wire v8 stores it (V20261018200500): a stencil instance is a reference by key
         * with a `children` slot for its fills, and pins no version (contract ADR 0006).
         */
        private const val MIGRATED_DRAFT_TEMPLATE_MODEL =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}, "linked": {"id": "linked", "type": "stencil", "slots": ["linked:children"], "props": {"stencilKey": "header"}}, "new": {"id": "new", "type": "stencil", "slots": ["new:children"], "props": {"stencilKey": "draft-only"}}}, "slots": {"linked:children": {"id": "linked:children", "name": "children", "nodeId": "linked", "children": []}, "new:children": {"id": "new:children", "name": "children", "nodeId": "new", "children": []}}, "marker": "catalog-v5"}"""
    }
}
