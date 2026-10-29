// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import db.migration.core.frozen.DataExampleKeys
import db.migration.core.frozen.Envelopes
import db.migration.core.frozen.FrozenToolkit
import db.migration.core.frozen.OneXContent
import db.migration.core.frozen.ReferenceWalker
import db.migration.core.frozen.StorageWrites
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp

/**
 * The 2.0 upgrade, step 8 of 16 (docs/migrations.md, "The 2.0 chain"): a 1.x catalog's templates
 * become its next draft's content in JSON storage (ADR 0033, step 7).
 *
 * A template variant holds one working model, and a template one data contract: what its author was
 * working on, its draft, else its latest published version, else its latest archived one
 * ([OneXContent]), in wire v8 with stencil instances by reference
 * ([db.migration.core.frozen.TemplateContentV8]), in the released form a release keeps
 * ([ReferenceWalker.releasedModel]). What the conversion reports is kept in `app_metadata` under
 * [REPORT_KEY].
 *
 * A template is a row naming its own content (`TemplateResource`: its theme, data contract, and each
 * variant by key, attributes and default) and one row per variant, of storage type `variant`, naming
 * the variant's content (its title, model and description). A data example is keyed by its name, as
 * wire v8 requires ([DataExampleKeys]). A variant is ready unless it, or its template's data contract,
 * has a draft that differs from what it last published.
 *
 * Self-contained (ADR 0035): JDBC and the frozen toolkit.
 */
@Suppress("ClassName")
class V20261101000800__core_2_0_templates_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    /** Pinned to this source and the toolkit (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = FrozenToolkit.pinned(CHECKSUM)

    private class Template(
        val tenant: String,
        val catalogId: String,
        val catalogKey: String,
        val versionId: String,
        val resourceId: String,
        val key: String,
        val name: String,
        val pdfaEnabled: Boolean,
        val themeKey: String?,
        val themeCatalogKey: String?,
        val createdAt: Timestamp,
        val updatedAt: Timestamp,
        val createdBy: String?,
        val updatedBy: String?,
    )

    private class Variant(
        val key: String,
        val title: String,
        val description: String?,
        val attributes: String?,
        val isDefault: Boolean,
        val createdAt: Timestamp,
        val updatedAt: Timestamp,
        val createdBy: String?,
        val updatedBy: String?,
    )

    override fun migrate(context: Context) {
        val store = StorageWrites(context.connection)
        val content = OneXContent.load(context.connection)
        val templates = store.query(
            """
            SELECT t.tenant_key, c.catalog_id::text, c.catalog_key::text, c.next_version_id::text, t.resource_id::text, t.id, t.name, t.pdfa_enabled,
                   theme.resource_key, theme_catalog.catalog_key::text, t.created_at, t.updated_at, t.created_by::text, t.updated_by::text
            FROM document_templates t
            JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_key = t.catalog_key
            LEFT JOIN catalog_resources theme ON theme.tenant_key = t.tenant_key AND theme.resource_id = t.theme_resource_id
            LEFT JOIN catalogs theme_catalog ON theme_catalog.tenant_key = theme.tenant_key AND theme_catalog.catalog_id = theme.catalog_id
            ORDER BY t.tenant_key, t.catalog_key, t.id
            """,
        ) { rs ->
            Template(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8),
                rs.getString(9), rs.getString(10), rs.getTimestamp(11), rs.getTimestamp(12), rs.getString(13), rs.getString(14),
            )
        }
        for (template in templates) {
            val variants = store.query(
                """
                SELECT id, title, description, attributes::text, is_default, created_at, updated_at, created_by::text, updated_by::text
                FROM template_variants
                WHERE tenant_key = ? AND template_resource_id = ?::uuid
                ORDER BY is_default DESC, created_at, id
                """,
                template.tenant,
                template.resourceId,
            ) { rs ->
                Variant(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                    rs.getTimestamp(6), rs.getTimestamp(7), rs.getString(8), rs.getString(9),
                )
            }
            if (variants.isEmpty()) log.warn("Template {} of tenant {} has no variant; it is kept with an empty one", template.key, template.tenant)

            // Each variant its own content: its title, its model whole, its description.
            val partHashes = variants.associate { variant ->
                val model = ReferenceWalker.releasedModel(content.workingModel(template.tenant, template.resourceId, variant.key), template.catalogKey)
                val part = Envelopes.variant(variant.title, model, metadata = null, description = variant.description)
                variant.key to store.store(template.tenant, "variant", part, ReferenceWalker.references(part))
            }
            val contract = content.workingContract(template.tenant, template.resourceId)
            val themeCatalogKey = template.themeCatalogKey?.takeUnless { template.themeKey == null || it == template.catalogKey }
            val payload = Envelopes.template(
                key = template.key,
                name = template.name,
                description = null,
                themeKey = template.themeKey,
                themeCatalogKey = themeCatalogKey,
                pdfaEnabled = template.pdfaEnabled,
                dataModel = contract?.dataModel,
                dataExamples = DataExampleKeys.wireForm(contract?.dataExamples ?: mapper.createArrayNode()),
                variants = variants.map { Envelopes.VariantEntry(it.key, it.attributes?.let(mapper::readTree), it.isDefault) },
            )
            val themeReference = template.themeKey?.let { setOf(ReferenceWalker.Reference(themeCatalogKey, "theme", it)) }.orEmpty()
            val hash = store.store(template.tenant, "template", payload, themeReference + variantAttributeReferences(payload.path("resource").path("variants")))
            store.row(
                StorageWrites.Row(
                    template.tenant, template.catalogId, template.versionId, "template", template.key, template.resourceId, hash, template.name,
                    createdAt = template.createdAt, updatedAt = template.updatedAt, createdBy = template.createdBy, updatedBy = template.updatedBy,
                ),
            )
            val contractUnpublished = content.hasUnpublishedContract(template.tenant, template.resourceId)
            for (variant in variants) {
                val variantId = store.query("SELECT variant_resource_id(?::uuid, ?)::text", template.resourceId, variant.key) { it.getString(1) }.single()
                val partHash = partHashes.getValue(variant.key)
                store.row(
                    StorageWrites.Row(
                        template.tenant, template.catalogId, template.versionId, "variant", variant.key, variantId, partHash, variant.title,
                        description = variant.description?.takeIf { it.isNotEmpty() }, parentId = template.resourceId,
                        createdAt = variant.createdAt, updatedAt = variant.updatedAt, createdBy = variant.createdBy, updatedBy = variant.updatedBy,
                    ),
                )
                if (!contractUnpublished && !content.hasUnpublishedModel(template.tenant, template.resourceId, variant.key)) {
                    val digest = store.query("SELECT template_variant_digest(?, ?)", hash, partHash) { it.getString(1) }.single()
                    store.ready(template.tenant, template.versionId, template.resourceId, variant.key, digest)
                }
            }
        }

        val report = content.converted.report
        if (report.isNotEmpty()) {
            store.update(
                "INSERT INTO app_metadata (key, value) VALUES (?, ?::jsonb) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()",
                REPORT_KEY,
                mapper.writeValueAsString(mapper.createArrayNode().addAll(report)),
            )
            report.forEach { log.warn("2.0 upgrade: {} {}", it.path("subject").asString(), it.path("message").asString()) }
        }
        log.info("2.0 upgrade: {} template(s) moved to JSON storage; {} notice(s) recorded under {}", templates.size, report.size, REPORT_KEY)
    }

    /** The attributes [variants] select by, as references (ADR 0039): `catalog.attribute` by that catalog's alias, a bare key by none. */
    private fun variantAttributeReferences(variants: tools.jackson.databind.JsonNode): Set<ReferenceWalker.Reference> = buildSet {
        variants.forEach { slot ->
            slot.get("attributes")?.takeIf { it.isObject }?.properties()?.forEach { (name, _) ->
                add(ReferenceWalker.Reference(name.substringBefore('.', "").ifEmpty { null }, "attribute", name.substringAfter('.')))
            }
        }
    }

    companion object {
        /** The `app_metadata` key the conversion's notices are kept under. */
        const val REPORT_KEY = "upgrade-2.0-template-content"

        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 1151385396
    }
}
