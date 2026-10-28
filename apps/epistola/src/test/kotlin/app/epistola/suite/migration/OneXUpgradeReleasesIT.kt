// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.revisions.ReleaseResourceStore
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.sha256Hex
import app.epistola.suite.environments.queries.GetGenerationReadiness
import app.epistola.suite.environments.queries.PUBLISHED_IN_1X
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.testing.TestRuntimeLifecycle
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.sql.DriverManager

/**
 * The 2.0 upgrade of a 1.x tenant, from its database to its first releases, as an installation goes
 * through it: a database 1.x left (the last 1.x schema, written as 1.x wrote it), the app starting on
 * it (Flyway with the 1.x-to-2.0 preflight and the 2.0 migrations, then the system catalog's
 * bootstrap), and the upgrade's startup work (each catalog's release, then the content hashes).
 *
 * Then every release the tenant has must hold what its record says it holds:
 *
 * - every font it retained carries its faces: the system catalog's bundled ones, binaries of its
 *   archive, and an authored font's uploaded one; and the authored theme keeps its spacing unit;
 * - every entry's recorded fingerprint is the fingerprint of the revision it points at;
 * - its content hash, and the fingerprint it was cut with, reproduce from what it retained. For the
 *   system catalog that is also the fingerprint of the archive the suite ships, which dependents pin:
 *   its fonts are resources of that archive.
 *
 * The 1.x rows are raw SQL on purpose: no command of today writes the 1.x schema.
 */
@Import(OneXUpgradeReleasesIT.OneXDatabase::class)
class OneXUpgradeReleasesIT : BaseIntegrationTest() {

    /** The database the app starts on: one that 1.x left, instead of an empty one. */
    @TestConfiguration(proxyBeanMethods = false)
    class OneXDatabase {
        @Bean
        @Primary
        fun oneXConnectionDetails(): JdbcConnectionDetails {
            val postgres = TestRuntimeLifecycle.postgres()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
                admin.createStatement().use {
                    it.execute("DROP DATABASE IF EXISTS \"$DATABASE\"")
                    it.execute("CREATE DATABASE \"$DATABASE\"")
                }
            }
            val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$DATABASE$1")
            Flyway.configure().dataSource(url, postgres.username, postgres.password).locations("classpath:db/migration").target(LAST_1X).load().migrate()
            DriverManager.getConnection(url, postgres.username, postgres.password).use { connection -> seed1x(connection) }
            return object : JdbcConnectionDetails {
                override fun getUsername(): String = postgres.username
                override fun getPassword(): String = postgres.password
                override fun getJdbcUrl(): String = url
            }
        }
    }

    @Autowired private lateinit var assembler: ReleaseContentAssembler

    @Autowired private lateinit var entries: ReleaseResourceStore

    @Autowired private lateinit var fingerprints: CatalogFingerprintService

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    private data class Recorded(val catalogKey: CatalogKey, val version: String, val fingerprint: String, val contentHash: String?, val namespace: String) {
        val catalogId: app.epistola.suite.common.ids.CatalogId get() = app.epistola.suite.common.ids.CatalogId(namespace, catalogKey, app.epistola.suite.common.ids.TenantId(TENANT))
    }

    @Test
    fun `every release of an upgraded 1x tenant holds what its record says it holds`() {
        // The app has started on the 1.x database: migrated, and the system catalog brought to the
        // bundled release. The upgrade cut no release of the authored catalog: it has its next draft
        // and, beside it, a draft of what it last published, which its author releases.
        assertThat(jdbi.withHandle<Boolean, Exception> { it.createQuery("SELECT to_regclass('catalog_upgrade_queue') IS NULL AND to_regclass('catalog_upgrade_published') IS NULL").mapTo(Boolean::class.java).one() })
            .`as`("the upgrade's tables are gone").isTrue()
        assertThat(jdbi.withHandle<Int, Exception> { it.createQuery("SELECT count(*) FROM cluster_tasks_scheduled WHERE task_key IN ('core.catalog-upgrade', 'core.release-content-hash')").mapTo(Int::class.java).one() })
            .`as`("no upgrade work is scheduled after startup").isZero()
        assertThat(releases().map { "${it.catalogKey.value}@${it.version}" }).`as`("only the system catalog's release: the upgrade cuts none").containsExactly("system@$BUNDLED_SYSTEM_VERSION")
        val letters = testCatalog(TENANT, LETTERS)
        assertThat(asTenant { GetGenerationReadiness(TENANT).query() }.publishedIn1x).`as`("the tenant home says what to release").containsExactly("Letters")
        val published = requireNotNull(jdbi.withHandle<String?, Exception> { it.createQuery("SELECT version_key FROM catalog_versions WHERE tenant_key = :t AND name = :n AND state = 'draft'").bind("t", TENANT).bind("n", PUBLISHED_IN_1X).mapTo(String::class.java).findOne().orElse(null) }) {
            "letters has no '$PUBLISHED_IN_1X' draft"
        }

        // Its author releases that draft: numbered the patch after its 1.x release, its use of the
        // system catalog (declared by name by the upgrade) pinned to the installed release.
        val result = asTenant { ReleaseCatalogDraft(letters, catalogVersion = CatalogReleaseRef(letters, DraftRef.Id(published))).execute() }
        assertThat(result.version).isEqualTo("1.1.1")
        assertThat(result.pinnedDependencies.map { "${it.catalogKey}@${it.releaseVersion}" }).containsExactly("system@$BUNDLED_SYSTEM_VERSION")
        assertThat(asTenant { GetGenerationReadiness(TENANT).query() }.publishedIn1x).isEmpty()

        val releases = releases()
        assertThat(releases.map { "${it.catalogKey.value}@${it.version}" }).containsExactlyInAnyOrder("system@$BUNDLED_SYSTEM_VERSION", "letters@1.1.1")
        assertThat(releases).`as`("every release records its content hash").allSatisfy { assertThat(it.contentHash).startsWith("v6:") }
        val invoice = requireNotNull(assembler.assemble(letters, "1.1.1")).content.resourceDetails.values.mapNotNull { it.resource as? TemplateResource }.single()
        assertThat(objectMapper.writeValueAsString(invoice.templateModel)).`as`("the release holds what 1.x published").contains("Dear customer").doesNotContain("Draft wording")
        val next = asTenant { app.epistola.suite.templates.queries.variants.GetVariantContent(app.epistola.suite.common.ids.VariantId(app.epistola.suite.common.ids.VariantKey.of("default"), app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of("invoice"), letters))).query() }
        assertThat(objectMapper.writeValueAsString(next)).`as`("the next draft keeps the unpublished 1.x work").contains("Draft wording")
        assertThat(invoice.dataExamples.orEmpty().map { it.key }).`as`("the published contract's example, by its key").containsExactly("default")

        val canonicalizer = CatalogCanonicalizer(objectMapper)
        val softly = SoftAssertions()
        for (release in releases) {
            val name = "${release.catalogKey.value}@${release.version}"
            val retained = requireNotNull(assembler.assemble(release.catalogId, release.version)) { "$name retained nothing" }.content

            val faces = retained.resourceDetails.values.mapNotNull { it.resource as? FontResource }
                .associate { font -> font.key to font.variants.map { Face(it.weight, it.italic, it.contentHash) }.toSet() }
            softly.assertThat(faces).`as`("the faces of every font $name retained").isEqualTo(expectedFaces.getValue(release.catalogKey))

            if (release.catalogKey == LETTERS) {
                val themes = retained.resourceDetails.values.mapNotNull { it.resource as? ThemeResource }.associate { it.key to it.spacingUnit }
                softly.assertThat(themes).`as`("the spacing unit of every theme $name retained").isEqualTo(mapOf("house" to 8f))
            }

            val reproduced = fingerprints.perResourceFingerprints(retained)
            for (entry in jdbi.withHandle<List<app.epistola.suite.catalog.revisions.ReleaseResource>, Exception> { entries.entriesOf(it, testCatalog(TENANT, release.catalogKey), release.version) }) {
                softly.assertThat(entry.fingerprint)
                    .`as`("$name records ${entry.contentKey} with the fingerprint of the revision it points at")
                    .isEqualTo(reproduced[entry.contentKey])
            }

            if (release.catalogKey == SYSTEM_CATALOG_KEY) {
                softly.assertThat(release.contentHash).`as`("$name is the archive the suite ships").isEqualTo(BUNDLED_SYSTEM_FINGERPRINT)
            }
            softly.assertThat(release.contentHash).`as`("$name's content hash, from what it retained").isEqualTo(canonicalizer.contentHash(retained))
            softly.assertThat(canonicalizer.matchesFingerprint(retained, release.fingerprint))
                .`as`("$name's retained content reproduces the fingerprint it was cut with, ${release.fingerprint}")
                .isTrue()
        }
        softly.assertAll()
    }

    private data class Face(val weight: Int, val italic: Boolean, val contentHash: String)

    /** The faces each catalog's fonts have, as 1.x left them: the bundled ones by their files' hashes, and the uploaded one. */
    private val expectedFaces: Map<CatalogKey, Map<String, Set<Face>>> = mapOf(
        SYSTEM_CATALOG_KEY to SYSTEM_FONTS.associate { font -> font.slug to BUNDLED_FACES.map { (weight, italic, token) -> Face(weight, italic, sha256Hex(classpathBytes(font.file(token)))) }.toSet() },
        LETTERS to mapOf("brand" to setOf(Face(400, false, sha256Hex(classpathBytes(BRAND_FACE))))),
    )

    private fun releases(): List<Recorded> = jdbi.withHandle<List<Recorded>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT c.catalog_key, r.version, r.fingerprint, r.content_hash, c.namespace FROM catalog_versions r
            JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
            WHERE r.state = 'released' AND r.tenant_key = :t ORDER BY c.catalog_key, r.version
            """,
        ).bind("t", TENANT).map { rs, _ -> Recorded(CatalogKey.of(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }.list()
    }

    /** As the catalog's author: the tenant's system principal, with the mediator. */
    private fun <T> asTenant(block: () -> T): T = SecurityContext.runWithPrincipal(SystemUser.principalForTenant(TENANT)) { MediatorContext.runWithMediator(mediator) { block() } }

    /** A bundled family as 1.x seeded it into the system catalog: 1.x recorded where its faces sat on the classpath ([location]). */
    private data class SystemFont(val slug: String, val name: String, val kind: String, val at: ((String) -> String)? = null) {
        fun location(token: String): String = at?.invoke(token) ?: "epistola/fonts/$slug/$slug-$token.ttf"

        /** Where the face's file is now: a binary of the system catalog, or the renderer's fallback. */
        fun file(token: String): String = at?.invoke(token) ?: "epistola/catalogs/system/binaries/fonts/$slug/$slug-$token.ttf"
    }

    companion object {
        private const val DATABASE = "onex_upgrade_releases"

        /** The last migration 1.x shipped (1.4.0 and 1.5.0 both end on it). */
        private const val LAST_1X = "20260923150918"

        private val TENANT = TenantKey.of("acme")
        private val LETTERS = CatalogKey.of("letters")

        /** The system catalog this suite ships (`catalogs/system/catalog.json`). */
        private const val BUNDLED_SYSTEM_VERSION = "2.1.0"
        private const val BUNDLED_SYSTEM_FINGERPRINT = "v6:3a56c7588b320e201173fe10dedd05dbe7fe364ecabbaa2b2761f8affeb12849"

        /** The file the authored font's uploaded face holds. */
        private const val BRAND_FACE = "epistola/catalogs/system/binaries/fonts/lato/lato-Regular.ttf"

        private val SYSTEM_FONTS = listOf(
            SystemFont("inter", "Inter", "sans"),
            SystemFont("source-sans-3", "Source Sans 3", "sans"),
            SystemFont("roboto", "Roboto", "sans"),
            SystemFont("lato", "Lato", "sans"),
            SystemFont("liberation-sans", "Liberation Sans", "sans") { token -> "fonts/LiberationSans-$token.ttf" },
            SystemFont("source-serif-4", "Source Serif 4", "serif"),
            SystemFont("merriweather", "Merriweather", "serif"),
            SystemFont("lora", "Lora", "serif"),
            SystemFont("jetbrains-mono", "JetBrains Mono", "mono"),
        )

        private val BUNDLED_FACES = listOf(Triple(400, false, "Regular"), Triple(700, false, "Bold"), Triple(400, true, "Italic"), Triple(700, true, "BoldItalic"))

        private fun classpathBytes(path: String): ByteArray = requireNotNull(OneXUpgradeReleasesIT::class.java.classLoader.getResourceAsStream(path)) { "not on the classpath: $path" }.use { it.readBytes() }

        /**
         * A 1.x tenant as 1.5.0 left it (compare a real 1.5.0 database): the system catalog subscribed
         * at the release 1.x shipped (1.3.0, with its bare-hex fingerprint), its bundled fonts seeded
         * beside it with classpath faces; and an authored catalog with an uploaded font, a theme in
         * the system catalog's Inter with a spacing unit of its own, and a template with a published
         * version and contract. The
         * system catalog's other resources are its manifest's, which the bootstrap puts in place at
         * the bundled release either way; its fonts are in the manifest too, and 1.x's seeded ones
         * are the same families, so the upgrade keeps them.
         */
        private fun seed1x(connection: java.sql.Connection) {
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                    INSERT INTO catalogs (id, tenant_key, name, type, source_url, installed_release_version, installed_fingerprint, installed_at)
                    VALUES ('system', 'acme', 'System', 'SUBSCRIBED', 'classpath:epistola/catalogs/system/catalog.json', '1.3.0',
                            '74c0b2d145c4a970830b5ccbeb092b20abe7c8e9559f1d6b368147bac4bc09ba', NOW());
                    INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind) VALUES
                    ${SYSTEM_FONTS.joinToString(",\n") { "('${it.slug}', 'acme', 'system', '${it.name}', '${it.kind}')" }};
                    INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', 'acme', 'Letters', 'AUTHORED');
                    -- A 1.x release: its metadata only, 1.x kept no release content. The upgrade deletes it.
                    INSERT INTO catalog_releases (tenant_key, catalog_key, version, fingerprint, manifest_snapshot)
                    VALUES ('acme', 'letters', '1.1.0', '${"9".repeat(64)}', '{}'::jsonb);
                    INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles, spacing_unit)
                    VALUES ('house', 'acme', 'letters', 'House', '{"fontFamily": {"slug": "inter", "catalogKey": "system"}}', 8);
                    INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind) VALUES ('brand', 'acme', 'letters', 'Brand', 'sans');
                    INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_resource_id)
                    SELECT 'invoice', 'acme', 'letters', 'Invoice', resource_id FROM themes WHERE tenant_key = 'acme' AND id = 'house';
                    INSERT INTO template_variants (id, tenant_key, template_resource_id, title, is_default)
                    SELECT 'default', 'acme', resource_id, 'Default', true FROM document_templates WHERE tenant_key = 'acme' AND id = 'invoice';
                    INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at)
                    SELECT 1, 'acme', resource_id, 'default',
                           '{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["root:children"]}, "body": {"id": "body", "type": "text", "slots": [], "props": {"content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Dear customer"}]}]}}}}, "slots": {"root:children": {"id": "root:children", "name": "children", "nodeId": "root", "children": ["body"]}}}',
                           'published', NOW()
                    FROM document_templates WHERE tenant_key = 'acme' AND id = 'invoice';
                    -- Unpublished work on top of it: the working copy, which 2.0 keeps as the next draft.
                    INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status)
                    SELECT 2, 'acme', resource_id, 'default',
                           '{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["root:children"]}, "body": {"id": "body", "type": "text", "slots": [], "props": {"content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Draft wording"}]}]}}}}, "slots": {"root:children": {"id": "root:children", "name": "children", "nodeId": "root", "children": ["body"]}}}',
                           'draft'
                    FROM document_templates WHERE tenant_key = 'acme' AND id = 'invoice';
                    INSERT INTO contract_versions (id, tenant_key, template_resource_id, data_model, data_examples, status, published_at)
                    SELECT 1, 'acme', resource_id, '{"type": "object", "properties": {"name": {"type": "string"}}}',
                           '[{"id": "8a6b4bde-2bb5-4d5e-9bd2-5a6f0b3a1c01", "name": "Default", "data": {"name": "World"}}]', 'published', NOW()
                    FROM document_templates WHERE tenant_key = 'acme' AND id = 'invoice';
                    """.trimIndent(),
                )
            }
            // The system catalog's faces point at the classpath, by the hash of the file 1.x found there.
            connection.prepareStatement(
                """
                INSERT INTO font_variants (tenant_key, font_resource_id, weight, italic, source, classpath_location, content_hash)
                SELECT 'acme', resource_id, ?, ?, 'CLASSPATH'::font_variant_source, ?, ? FROM fonts WHERE tenant_key = 'acme' AND catalog_key = 'system' AND slug = ?
                """.trimIndent(),
            ).use { insert ->
                for (font in SYSTEM_FONTS) {
                    for ((weight, italic, token) in BUNDLED_FACES) {
                        insert.setInt(1, weight)
                        insert.setBoolean(2, italic)
                        insert.setString(3, font.location(token))
                        insert.setString(4, sha256Hex(classpathBytes(font.file(token))))
                        insert.setString(5, font.slug)
                        insert.addBatch()
                    }
                }
                insert.executeBatch()
            }
            // The authored font's face is an upload: an asset, its bytes in the asset store.
            val bytes = classpathBytes(BRAND_FACE)
            val hash = sha256Hex(bytes)
            connection.prepareStatement("INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes) VALUES ('global', ?, ?, 'font/ttf', ?)").use {
                it.setString(1, hash)
                it.setBytes(2, bytes)
                it.setLong(3, bytes.size.toLong())
                it.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes, content_hash)
                VALUES ('0199f3a0-0000-7000-8000-00000000b001', 'acme', 'letters', 'brand-regular.ttf', 'font/ttf', ?, ?)
                """.trimIndent(),
            ).use {
                it.setLong(1, bytes.size.toLong())
                it.setString(2, hash)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO font_variants (tenant_key, font_resource_id, weight, italic, source, asset_resource_id, content_hash)
                SELECT 'acme', f.resource_id, 400, false, 'ASSET'::font_variant_source, a.resource_id, ?
                FROM fonts f, assets a
                WHERE f.tenant_key = 'acme' AND f.catalog_key = 'letters' AND f.slug = 'brand' AND a.tenant_key = 'acme' AND a.id = '0199f3a0-0000-7000-8000-00000000b001'
                """.trimIndent(),
            ).use {
                it.setString(1, hash)
                it.executeUpdate()
            }
        }
    }
}
