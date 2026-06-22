package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.migrations.AtRestContentMigrator.Companion.CONTENT_SCHEMA_VERSION_KEY
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** Key present only in this test's seeded blob, so the table-wide scan tags nothing else. */
private const val SENTINEL = "_attestAtRest"

/**
 * End-to-end check of [AtRestContentMigrator] against a real Postgres: it drives
 * the migration off the single global `app_metadata` counter, rewrites every
 * carrier blob through the shared chain, and advances the counter atomically.
 * The live chain is empty, so the migrating cases use an explicit version window.
 */
class AtRestContentMigratorTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var appMetadata: AppMetadataService

    /**
     * A 1→2 step that tags only this test's own blob with `"migrated": true`. The
     * at-rest scan is table-wide and shares the DB with other test classes, so it
     * hands this step their blobs too (including array blobs like `data_examples`);
     * key on a sentinel so only the row this test seeded is modified.
     */
    private class TagMigration(override val from: Int) : CatalogSchemaMigration {
        override fun migrateContentBlob(blobType: String, blob: JsonNode, ctx: MigrationContext): JsonNode = if (blob is ObjectNode && blob.has(SENTINEL)) blob.put("migrated", true) else blob
    }

    private fun seedTheme(tenant: TenantKey, slug: String, styles: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles, created_at, updated_at)
                VALUES (:id, :tenantKey, :catalogKey, :name, :documentStyles::jsonb, NOW(), NOW())
                """,
            )
                .bind("id", slug)
                .bind("tenantKey", tenant)
                .bind("catalogKey", CatalogKey.DEFAULT)
                .bind("name", "Theme $slug")
                .bind("documentStyles", styles)
                .execute()
        }
    }

    private fun themeStyles(tenant: TenantKey, slug: String): String = jdbi.withHandle<String, Exception> { h ->
        h.createQuery("SELECT document_styles::text FROM themes WHERE tenant_key = :t AND catalog_key = :c AND id = :id")
            .bind("t", tenant)
            .bind("c", CatalogKey.DEFAULT)
            .bind("id", slug)
            .mapTo(String::class.java)
            .one()
    }

    private fun storedVersion(): Int? = appMetadata.getAs<Int>(CONTENT_SCHEMA_VERSION_KEY)

    @Test
    fun `migrateAll rewrites content and advances the counter when behind`() {
        val tenant = createTenant("At-Rest Migration Test").id
        appMetadata.setAs(CONTENT_SCHEMA_VERSION_KEY, 1)
        seedTheme(tenant, "lagging", styles = """{"font":"Helvetica","$SENTINEL":"1"}""")

        val schemaMigrator = CatalogSchemaMigrator(objectMapper, listOf(TagMigration(from = 1)), current = 2, baseline = 1)
        AtRestContentMigrator(jdbi, objectMapper, schemaMigrator).migrateAll()

        assertThat(storedVersion()).isEqualTo(2)
        val styles = themeStyles(tenant, "lagging")
        assertThat(styles).contains("migrated") // tagged by the chain
        assertThat(styles).contains("Helvetica") // original content preserved
    }

    @Test
    fun `migrateAll is a no-op when the counter is already current`() {
        val tenant = createTenant("Already Current Test").id
        appMetadata.setAs(CONTENT_SCHEMA_VERSION_KEY, 4) // == live CATALOG_MANIFEST_SCHEMA_VERSION
        seedTheme(tenant, "untouched", styles = """{"font":"Georgia"}""")

        // Live constants: baseline == current == 4, empty chain.
        val report = AtRestContentMigrator(jdbi, objectMapper, CatalogSchemaMigrator(objectMapper, emptyList(), current = 4, baseline = 4)).migrateAll()

        assertThat(report.total).isEqualTo(0)
        assertThat(storedVersion()).isEqualTo(4)
        assertThat(themeStyles(tenant, "untouched")).doesNotContain("migrated")
    }

    @Test
    fun `migrateAll seeds the counter to current on first run`() {
        jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM app_metadata WHERE key = :k").bind("k", CONTENT_SCHEMA_VERSION_KEY).execute() }

        val report = AtRestContentMigrator(jdbi, objectMapper, CatalogSchemaMigrator(objectMapper, emptyList(), current = 4, baseline = 4)).migrateAll()

        assertThat(report.total).isEqualTo(0)
        assertThat(storedVersion()).isEqualTo(4) // adopted current without migrating
    }

    @Test
    fun `migrateAll skips SUBSCRIBED content`() {
        val tenant = createTenant("Subscribed Skip").id
        jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO catalogs (id, tenant_key, name, type, source_url, created_at, updated_at) " +
                    "VALUES ('sub', :t, 'Sub', 'SUBSCRIBED', 'https://example.test/catalog.json', NOW(), NOW())",
            ).bind("t", tenant).execute()
            h.createUpdate(
                "INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles, created_at, updated_at) " +
                    "VALUES ('sub-theme', :t, 'sub', 'Sub', :ds::jsonb, NOW(), NOW())",
            ).bind("t", tenant).bind("ds", """{"$SENTINEL":"x"}""").execute()
        }
        appMetadata.setAs(CONTENT_SCHEMA_VERSION_KEY, 1)

        val schemaMigrator = CatalogSchemaMigrator(objectMapper, listOf(TagMigration(from = 1)), current = 2, baseline = 1)
        AtRestContentMigrator(jdbi, objectMapper, schemaMigrator).migrateAll()

        // The SUBSCRIBED theme carries the sentinel but is never scanned, so untouched.
        val subStyles = jdbi.withHandle<String, Exception> { h ->
            h.createQuery("SELECT document_styles::text FROM themes WHERE tenant_key = :t AND catalog_key = 'sub' AND id = 'sub-theme'")
                .bind("t", tenant).mapTo(String::class.java).one()
        }
        assertThat(subStyles).doesNotContain("migrated")
    }
}
