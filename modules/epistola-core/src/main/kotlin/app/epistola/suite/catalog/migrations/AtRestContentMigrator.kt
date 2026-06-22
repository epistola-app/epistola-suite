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
 * [CatalogSchemaMigrator]. Scans **AUTHORED catalogs only**: those have no source
 * URL to re-fetch, so an in-place pass is the only way to migrate them when the
 * app is upgraded against an existing database. SUBSCRIBED content is left alone —
 * its source is authoritative and the wire path re-migrates it on upgrade, so
 * mutating it locally would only drift it from source until the next refresh.
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
 * `AtRestContentMigrationBootstrap`. Rows the chain leaves unchanged are **not
 * rewritten** (a migration that touches only one resource type skips the rest),
 * so the pass is byte-stable for untouched content. Idempotent and a cheap no-op
 * while the chain is empty. See `docs/adr/0007-at-rest-resource-migration.md`.
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
            SELECT t.tenant_key::text AS tenant_key, t.catalog_key::text AS catalog_key, t.id::text AS id,
                   t.document_styles::text AS document_styles,
                   t.page_settings::text AS page_settings,
                   t.block_style_presets::text AS block_style_presets
            FROM themes t
            JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.id = t.catalog_key
            WHERE c.type = 'AUTHORED'
            """,
        ).mapToMap().list()

        var updated = 0
        for (row in rows) {
            val documentStyles = migrateBlob(row["document_styles"] as String?, ContentBlobType.DOCUMENT_STYLES, stored)
            val pageSettings = migrateBlob(row["page_settings"] as String?, ContentBlobType.PAGE_SETTINGS, stored)
            val blockStylePresets = migrateBlob(row["block_style_presets"] as String?, ContentBlobType.BLOCK_STYLE_PRESETS, stored)
            if (!documentStyles.changed && !pageSettings.changed && !blockStylePresets.changed) continue
            handle.createUpdate(
                """
                UPDATE themes
                SET document_styles = :documentStyles::jsonb,
                    page_settings = :pageSettings::jsonb,
                    block_style_presets = :blockStylePresets::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND id = :id
                """,
            ).also {
                bindJson(it, "documentStyles", documentStyles.json)
                bindJson(it, "pageSettings", pageSettings.json)
                bindJson(it, "blockStylePresets", blockStylePresets.json)
            }
                .bind("tenantKey", row["tenant_key"])
                .bind("catalogKey", row["catalog_key"])
                .bind("id", row["id"])
                .execute()
            updated++
        }
        return updated
    }

    private fun migrateTemplateVersions(handle: Handle, stored: Int): Int {
        val rows = handle.createQuery(
            """
            SELECT tv.tenant_key::text AS tenant_key, tv.catalog_key::text AS catalog_key,
                   tv.template_key::text AS template_key, tv.variant_key::text AS variant_key, tv.id,
                   tv.template_model::text AS template_model
            FROM template_versions tv
            JOIN catalogs c ON c.tenant_key = tv.tenant_key AND c.id = tv.catalog_key
            WHERE c.type = 'AUTHORED'
            """,
        ).mapToMap().list()

        var updated = 0
        for (row in rows) {
            val templateModel = migrateBlob(row["template_model"] as String?, ContentBlobType.TEMPLATE_MODEL, stored)
            if (!templateModel.changed) continue
            handle.createUpdate(
                """
                UPDATE template_versions
                SET template_model = :templateModel::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND variant_key = :variantKey AND id = :id
                """,
            ).also { bindJson(it, "templateModel", templateModel.json) }
                .bind("tenantKey", row["tenant_key"])
                .bind("catalogKey", row["catalog_key"])
                .bind("templateKey", row["template_key"])
                .bind("variantKey", row["variant_key"])
                .bind("id", row["id"])
                .execute()
            updated++
        }
        return updated
    }

    private fun migrateStencilVersions(handle: Handle, stored: Int): Int {
        val rows = handle.createQuery(
            """
            SELECT sv.tenant_key::text AS tenant_key, sv.catalog_key::text AS catalog_key,
                   sv.stencil_key::text AS stencil_key, sv.id, sv.content::text AS content
            FROM stencil_versions sv
            JOIN catalogs c ON c.tenant_key = sv.tenant_key AND c.id = sv.catalog_key
            WHERE c.type = 'AUTHORED'
            """,
        ).mapToMap().list()

        var updated = 0
        for (row in rows) {
            val content = migrateBlob(row["content"] as String?, ContentBlobType.STENCIL_CONTENT, stored)
            if (!content.changed) continue
            handle.createUpdate(
                """
                UPDATE stencil_versions
                SET content = :content::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND stencil_key = :stencilKey AND id = :id
                """,
            ).also { bindJson(it, "content", content.json) }
                .bind("tenantKey", row["tenant_key"])
                .bind("catalogKey", row["catalog_key"])
                .bind("stencilKey", row["stencil_key"])
                .bind("id", row["id"])
                .execute()
            updated++
        }
        return updated
    }

    private fun migrateContractVersions(handle: Handle, stored: Int): Int {
        val rows = handle.createQuery(
            """
            SELECT cv.tenant_key::text AS tenant_key, cv.catalog_key::text AS catalog_key,
                   cv.template_key::text AS template_key, cv.id,
                   cv.schema::text AS schema,
                   cv.data_model::text AS data_model,
                   cv.data_examples::text AS data_examples
            FROM contract_versions cv
            JOIN catalogs c ON c.tenant_key = cv.tenant_key AND c.id = cv.catalog_key
            WHERE c.type = 'AUTHORED'
            """,
        ).mapToMap().list()

        var updated = 0
        for (row in rows) {
            val schema = migrateBlob(row["schema"] as String?, ContentBlobType.CONTRACT_SCHEMA, stored)
            val dataModel = migrateBlob(row["data_model"] as String?, ContentBlobType.DATA_MODEL, stored)
            val dataExamples = migrateBlob(row["data_examples"] as String?, ContentBlobType.DATA_EXAMPLES, stored)
            if (!schema.changed && !dataModel.changed && !dataExamples.changed) continue
            handle.createUpdate(
                """
                UPDATE contract_versions
                SET schema = :schema::jsonb,
                    data_model = :dataModel::jsonb,
                    data_examples = :dataExamples::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND id = :id
                """,
            ).also {
                bindJson(it, "schema", schema.json)
                bindJson(it, "dataModel", dataModel.json)
                bindJson(it, "dataExamples", dataExamples.json)
            }
                .bind("tenantKey", row["tenant_key"])
                .bind("catalogKey", row["catalog_key"])
                .bind("templateKey", row["template_key"])
                .bind("id", row["id"])
                .execute()
            updated++
        }
        return updated
    }

    private data class BlobResult(val json: String?, val changed: Boolean)

    /**
     * Run the chain over a stored blob. Returns the (re-serialized) JSON and
     * whether the chain actually changed it. A blob the chain leaves alone is
     * reported unchanged so the row can be skipped — a migration that doesn't
     * touch a given row leaves it byte-identical, with no needless write or
     * `updated_at` churn. Null → null, unchanged.
     */
    private fun migrateBlob(json: String?, blobType: String, stored: Int): BlobResult {
        if (json == null) return BlobResult(null, changed = false)
        val original = objectMapper.readTree(json)
        val before = original.deepCopy()
        val migrated = migrator.migrateContentBlob(blobType, original, stored)
        return if (migrated == before) BlobResult(json, changed = false) else BlobResult(objectMapper.writeValueAsString(migrated), changed = true)
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
