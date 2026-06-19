package app.epistola.suite.catalog.migrations

import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.Update
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.Types

/**
 * Upgrades **already-stored** bucket-C content blobs to the current content
 * version, the at-rest counterpart to the import-boundary
 * [CatalogSchemaMigrator]. Reaches content the wire path cannot: AUTHORED /
 * user-authored resources have no source URL to re-fetch, so only an in-place
 * pass migrates them when the app is upgraded against an existing database.
 *
 * Versioning is **one global counter**, not a per-row column — modelled on
 * Flyway/EF (one history pointer for the whole database): the app code is always
 * current, so at any moment every stored blob is at one content version. A single
 * `app_metadata` key ([CONTENT_SCHEMA_VERSION_KEY]) records it. On boot, if that
 * counter is below current, the whole pass runs the shared
 * [CatalogSchemaMigration] chain over **every** carrier blob and bumps the
 * counter — all in one transaction, so a failure rolls back cleanly and the next
 * boot retries with no double-apply.
 *
 * Because the counter cannot distinguish a freshly-written current blob from a
 * lagging one, this must run **before** any bootstrap that writes current-shape
 * content (`DemoLoader`, `SystemCatalogBootstrap`) — see
 * `AtRestContentMigrationBootstrap`. Idempotent and a cheap no-op while the chain
 * is empty. See `docs/adr/0007-at-rest-resource-migration.md`.
 */
@Component
class AtRestContentMigrator(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val migrator: CatalogSchemaMigrator,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    data class Report(
        val themes: Int = 0,
        val templateVersions: Int = 0,
        val stencilVersions: Int = 0,
        val contractVersions: Int = 0,
    ) {
        val total: Int get() = themes + templateVersions + stencilVersions + contractVersions
    }

    /**
     * Read the global content version; if it is below current, migrate every
     * carrier blob and advance the counter — atomically. Returns the per-carrier
     * row counts (all zero on a no-op / first-run init).
     */
    fun migrateAll(): Report {
        val current = migrator.currentVersion
        return jdbi.inTransaction<Report, Exception> { handle ->
            val stored = readVersion(handle)
            when {
                // First run (fresh or pre-tracking) — the app writes current
                // shape, so adopt current without migrating. Seeds the counter so
                // every later boot has a baseline to compare against.
                stored == null -> {
                    writeVersion(handle, current)
                    Report()
                }
                stored == current -> Report()
                stored > current -> {
                    logger.error(
                        "Stored content version v{} is newer than this instance (v{}); not downgrading. Upgrade Epistola.",
                        stored,
                        current,
                    )
                    Report()
                }
                // stored < current, but no chain (transitional / dropped below
                // baseline): adopt current as-is, matching the wire gate.
                !migrator.hasMigrations -> {
                    writeVersion(handle, current)
                    Report()
                }
                else -> {
                    val report = Report(
                        themes = migrateThemes(handle, stored),
                        templateVersions = migrateTemplateVersions(handle, stored),
                        stencilVersions = migrateStencilVersions(handle, stored),
                        contractVersions = migrateContractVersions(handle, stored),
                    )
                    writeVersion(handle, current)
                    logger.info(
                        "At-rest content migration upgraded {} row(s) from schema v{} to v{} — themes={}, templateVersions={}, stencilVersions={}, contractVersions={}.",
                        report.total,
                        stored,
                        current,
                        report.themes,
                        report.templateVersions,
                        report.stencilVersions,
                        report.contractVersions,
                    )
                    report
                }
            }
        }
    }

    private fun readVersion(handle: Handle): Int? = handle
        .createQuery("SELECT value::text FROM app_metadata WHERE key = :key")
        .bind("key", CONTENT_SCHEMA_VERSION_KEY)
        .mapTo(String::class.java)
        .findOne()
        .map { it.trim().toInt() }
        .orElse(null)

    private fun writeVersion(handle: Handle, version: Int) {
        handle.createUpdate(
            """
            INSERT INTO app_metadata (key, value, updated_at)
            VALUES (:key, :value::jsonb, NOW())
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
            """,
        )
            .bind("key", CONTENT_SCHEMA_VERSION_KEY)
            .bind("value", version.toString())
            .execute()
    }

    private fun migrateThemes(handle: Handle, stored: Int): Int {
        val rows = handle.createQuery(
            """
            SELECT tenant_key::text AS tenant_key, catalog_key::text AS catalog_key, id::text AS id,
                   document_styles::text AS document_styles,
                   page_settings::text AS page_settings,
                   block_style_presets::text AS block_style_presets
            FROM themes
            """,
        ).mapToMap().list()

        for (row in rows) {
            val update = handle.createUpdate(
                """
                UPDATE themes
                SET document_styles = :documentStyles::jsonb,
                    page_settings = :pageSettings::jsonb,
                    block_style_presets = :blockStylePresets::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND id = :id
                """,
            )
            bindJson(update, "documentStyles", migrate(row["document_styles"] as String?, ContentBlobType.DOCUMENT_STYLES, stored))
            bindJson(update, "pageSettings", migrate(row["page_settings"] as String?, ContentBlobType.PAGE_SETTINGS, stored))
            bindJson(update, "blockStylePresets", migrate(row["block_style_presets"] as String?, ContentBlobType.BLOCK_STYLE_PRESETS, stored))
            update
                .bind("tenantKey", row["tenant_key"])
                .bind("catalogKey", row["catalog_key"])
                .bind("id", row["id"])
                .execute()
        }
        return rows.size
    }

    private fun migrateTemplateVersions(handle: Handle, stored: Int): Int {
        val rows = handle.createQuery(
            """
            SELECT tenant_key::text AS tenant_key, catalog_key::text AS catalog_key,
                   template_key::text AS template_key, variant_key::text AS variant_key, id,
                   template_model::text AS template_model
            FROM template_versions
            """,
        ).mapToMap().list()

        for (row in rows) {
            val update = handle.createUpdate(
                """
                UPDATE template_versions
                SET template_model = :templateModel::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND variant_key = :variantKey AND id = :id
                """,
            )
            bindJson(update, "templateModel", migrate(row["template_model"] as String?, ContentBlobType.TEMPLATE_DOCUMENT, stored))
            update
                .bind("tenantKey", row["tenant_key"])
                .bind("catalogKey", row["catalog_key"])
                .bind("templateKey", row["template_key"])
                .bind("variantKey", row["variant_key"])
                .bind("id", row["id"])
                .execute()
        }
        return rows.size
    }

    private fun migrateStencilVersions(handle: Handle, stored: Int): Int {
        val rows = handle.createQuery(
            """
            SELECT tenant_key::text AS tenant_key, catalog_key::text AS catalog_key,
                   stencil_key::text AS stencil_key, id, content::text AS content
            FROM stencil_versions
            """,
        ).mapToMap().list()

        for (row in rows) {
            val update = handle.createUpdate(
                """
                UPDATE stencil_versions
                SET content = :content::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND stencil_key = :stencilKey AND id = :id
                """,
            )
            bindJson(update, "content", migrate(row["content"] as String?, ContentBlobType.TEMPLATE_DOCUMENT, stored))
            update
                .bind("tenantKey", row["tenant_key"])
                .bind("catalogKey", row["catalog_key"])
                .bind("stencilKey", row["stencil_key"])
                .bind("id", row["id"])
                .execute()
        }
        return rows.size
    }

    private fun migrateContractVersions(handle: Handle, stored: Int): Int {
        val rows = handle.createQuery(
            """
            SELECT tenant_key::text AS tenant_key, catalog_key::text AS catalog_key,
                   template_key::text AS template_key, id,
                   schema::text AS schema,
                   data_model::text AS data_model,
                   data_examples::text AS data_examples
            FROM contract_versions
            """,
        ).mapToMap().list()

        for (row in rows) {
            val update = handle.createUpdate(
                """
                UPDATE contract_versions
                SET schema = :schema::jsonb,
                    data_model = :dataModel::jsonb,
                    data_examples = :dataExamples::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND id = :id
                """,
            )
            bindJson(update, "schema", migrate(row["schema"] as String?, ContentBlobType.CONTRACT_SCHEMA, stored))
            bindJson(update, "dataModel", migrate(row["data_model"] as String?, ContentBlobType.DATA_MODEL, stored))
            bindJson(update, "dataExamples", migrate(row["data_examples"] as String?, ContentBlobType.DATA_EXAMPLES, stored))
            update
                .bind("tenantKey", row["tenant_key"])
                .bind("catalogKey", row["catalog_key"])
                .bind("templateKey", row["template_key"])
                .bind("id", row["id"])
                .execute()
        }
        return rows.size
    }

    /** Parse the stored JSON, run the chain from [stored] to current, re-serialize. Null → null. */
    private fun migrate(json: String?, blobType: String, stored: Int): String? {
        if (json == null) return null
        val migrated = migrator.migrateContentBlob(blobType, objectMapper.readTree(json), stored)
        return objectMapper.writeValueAsString(migrated)
    }

    /** Bind a JSON string for a `:name::jsonb` placeholder, NULL-safe. */
    private fun bindJson(update: Update, name: String, json: String?) {
        if (json == null) update.bindNull(name, Types.VARCHAR) else update.bind(name, json)
    }

    companion object {
        /** Installation-wide `app_metadata` key holding the stored content schema version. */
        const val CONTENT_SCHEMA_VERSION_KEY = "catalog.content.schema_version"
    }
}
