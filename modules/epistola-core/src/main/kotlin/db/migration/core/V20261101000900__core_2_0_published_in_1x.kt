// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import db.migration.core.frozen.DataExampleKeys
import db.migration.core.frozen.FrozenToolkit
import db.migration.core.frozen.OneXContent
import db.migration.core.frozen.ReferenceWalker
import db.migration.core.frozen.StorageWrites
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The 2.0 upgrade, step 9 of 16 (docs/migrations.md, "The 2.0 chain"): what a 1.x catalog last
 * published becomes a draft of its own (ADR 0035); no release is cut. Every authored 1.x catalog keeps
 * its next draft, the working copy with any unpublished 1.x work, and gets beside it a named draft
 * **Published in 1.x** holding what each of its templates and stencils last published, which its
 * author releases and deploys to restore what was live. The other types were never versioned in 1.x:
 * their draft is what was published, so the named draft shares their contents.
 *
 * The named draft starts as a copy of the next draft, on the same base, then:
 * - a template whose default variant was never published is left out, and so is a variant never
 *   published; each published variant's model replaces the draft's, in its released form
 *   ([ReferenceWalker.releasedModel]); a published data contract replaces the draft's data model and
 *   examples, each example by its key ([DataExampleKeys]);
 * - a stencil never published is left out; a published one's content and parameter schema replace the
 *   draft's, in released form ([ReferenceWalker.releasedForm]);
 * - every part of it is marked ready: it was published.
 * A row whose content changes counts as changed (its revision is raised), as any edit of a draft row.
 * When the result holds what the next draft holds, the named draft is removed: there is nothing of its
 * own to release. A subscribed 1.x catalog gets its release from its source at boot.
 *
 * The authored 1.x catalogs are listed in `app_metadata` ([ONE_X_CATALOGS]), so the tenant home asks
 * for each to be released and deployed while it has no release.
 *
 * Self-contained (ADR 0035): JDBC and the frozen toolkit.
 */
@Suppress("ClassName")
class V20261101000900__core_2_0_published_in_1x : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    /** Pinned to this source and the toolkit (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = FrozenToolkit.pinned(CHECKSUM)

    private class Catalog(val tenant: String, val catalogId: String, val catalogKey: String, val next: String)

    private class Row(val resourceId: String, val key: String, val hash: String)

    override fun migrate(context: Context) {
        val store = StorageWrites(context.connection)
        val content = OneXContent.load(context.connection)
        val catalogs = store.query(
            "SELECT tenant_key, catalog_id::text, catalog_key::text, next_version_id::text FROM catalogs WHERE type = 'AUTHORED' ORDER BY tenant_key, catalog_key",
        ) { Catalog(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
        var created = 0
        for (catalog in catalogs) {
            if (publishedDraft(store, content, catalog)) created++
        }
        store.update(
            """
            INSERT INTO app_metadata (key, value)
            SELECT ?, jsonb_agg(jsonb_build_object('tenant', c.tenant_key, 'catalog', c.catalog_id::text) ORDER BY c.tenant_key, c.catalog_id)
            FROM catalogs c
            WHERE c.type = 'AUTHORED'
            HAVING count(*) > 0
            """,
            ONE_X_CATALOGS,
        )
        if (catalogs.isNotEmpty()) log.info("2.0 upgrade: {} of {} authored catalog(s) have a '{}' draft to release", created, catalogs.size, NAME)
    }

    /** The named draft of what [catalog] published; false when it would hold what its next draft holds. */
    private fun publishedDraft(store: StorageWrites, content: OneXContent.Content, catalog: Catalog): Boolean {
        val draft = store.query(
            """
            INSERT INTO catalog_versions (tenant_key, catalog_id, state, name, base_version, default_theme_resource_id, presets)
            SELECT tenant_key, catalog_id, 'draft', ?, base_version, default_theme_resource_id, presets
            FROM catalog_versions WHERE tenant_key = ? AND version_id = ?::uuid
            RETURNING version_id::text
            """,
            NAME,
            catalog.tenant,
            catalog.next,
        ) { it.getString(1) }.single()
        // A copy of the next draft: its rows, ready marks and code list refreshes.
        store.update(
            """
            INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id, parent_id, content_hash,
                fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)
            SELECT tenant_key, catalog_id, ?::uuid, resource_type, resource_key, resource_id, parent_id, content_hash,
                fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by
            FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid
            """,
            draft,
            catalog.tenant,
            catalog.next,
        )
        store.update(
            """
            INSERT INTO code_list_refreshes (tenant_key, version_id, resource_id, last_refreshed_at, last_refresh_error)
            SELECT tenant_key, ?::uuid, resource_id, last_refreshed_at, last_refresh_error FROM code_list_refreshes WHERE tenant_key = ? AND version_id = ?::uuid
            """,
            draft,
            catalog.tenant,
            catalog.next,
        )
        templates(store, content, catalog, draft)
        stencils(store, content, catalog, draft)
        if (same(store, catalog.tenant, catalog.next, draft)) {
            store.update("DELETE FROM catalog_versions WHERE tenant_key = ? AND version_id = ?::uuid", catalog.tenant, draft)
            return false
        }
        // It was published: every part of it ready, as it is now.
        store.update(
            """
            INSERT INTO resource_ready_marks (tenant_key, version_id, resource_id, part, digest)
            SELECT r.tenant_key, r.version_id, r.resource_id, p.resource_key, template_variant_digest(r.content_hash, p.content_hash)
            FROM release_resources r
            JOIN release_resources p ON p.tenant_key = r.tenant_key AND p.version_id = r.version_id AND p.parent_id = r.resource_id AND p.resource_type = 'variant'
            WHERE r.tenant_key = ? AND r.version_id = ?::uuid AND r.resource_type = 'template'
            UNION ALL
            SELECT r.tenant_key, r.version_id, r.resource_id, '', r.content_hash
            FROM release_resources r
            WHERE r.tenant_key = ? AND r.version_id = ?::uuid AND r.resource_type NOT IN ('template', 'variant')
            """,
            catalog.tenant,
            draft,
            catalog.tenant,
            draft,
        )
        return true
    }

    private fun rows(store: StorageWrites, tenant: String, versionId: String, type: String): List<Row> = store.query(
        "SELECT resource_id::text, resource_key, content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND resource_type = ? AND parent_id IS NULL ORDER BY resource_key",
        tenant,
        versionId,
        type,
    ) { Row(it.getString(1), it.getString(2), it.getString(3)) }

    private fun templates(store: StorageWrites, content: OneXContent.Content, catalog: Catalog, draft: String) {
        for (template in rows(store, catalog.tenant, draft, "template")) {
            val head = payload(store, catalog.tenant, template.hash) as ObjectNode
            val resource = head.get("resource") as ObjectNode
            val entries = (resource.get("variants") as? ArrayNode)?.toList().orEmpty()
            val default = entries.firstOrNull { it.get("isDefault")?.asBoolean() == true } ?: entries.firstOrNull()
            val published = entries.mapNotNull { it.get("key")?.asString() }
                .associateWith { content.publishedModel(catalog.tenant, template.resourceId, it) }
                .filterValues { it != null }
            val defaultKey = default?.get("key")?.asString()
            if (defaultKey == null || defaultKey !in published) {
                // Before 2.0 a template rendered through its default variant: never published, it was not live.
                store.update(
                    "DELETE FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND (resource_id = ?::uuid OR parent_id = ?::uuid)",
                    catalog.tenant,
                    draft,
                    template.resourceId,
                    template.resourceId,
                )
                continue
            }
            val variants = store.query(
                "SELECT resource_id::text, resource_key, content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND parent_id = ?::uuid ORDER BY resource_key",
                catalog.tenant,
                draft,
                template.resourceId,
            ) { Row(it.getString(1), it.getString(2), it.getString(3)) }
            for (variant in variants) {
                val model = published[variant.key]
                if (model == null) {
                    store.update("DELETE FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND parent_id = ?::uuid AND resource_key = ?", catalog.tenant, draft, template.resourceId, variant.key)
                    continue
                }
                val part = payload(store, catalog.tenant, variant.hash) as ObjectNode
                (part.get("resource") as ObjectNode).set("templateModel", ReferenceWalker.releasedModel(model, catalog.catalogKey))
                val hash = store.store(catalog.tenant, "variant", part, ReferenceWalker.references(part))
                changed(store, hash, catalog.tenant, draft, "parent_id = ?::uuid AND resource_key = ?", template.resourceId, variant.key)
            }
            resource.set("variants", mapper.createArrayNode().also { kept -> entries.filter { it.get("key")?.asString() in published }.forEach { kept.add(it) } })
            content.publishedContract(catalog.tenant, template.resourceId)?.let { contract ->
                resource.set("dataModel", contract.dataModel?.takeUnless { it.isNull } ?: mapper.nullNode())
                resource.set("dataExamples", DataExampleKeys.wireForm(contract.dataExamples ?: mapper.createArrayNode()))
            }
            // A template's own reference, its theme, is not in what changed: it carries over.
            val theme = resource.text("themeKey")?.let { setOf(ReferenceWalker.Reference(resource.text("themeCatalogKey"), "theme", it)) }.orEmpty()
            val hash = store.store(catalog.tenant, "template", head, theme)
            changed(store, hash, catalog.tenant, draft, "resource_id = ?::uuid AND parent_id IS NULL", template.resourceId)
        }
    }

    private fun stencils(store: StorageWrites, content: OneXContent.Content, catalog: Catalog, draft: String) {
        for (stencil in rows(store, catalog.tenant, draft, "stencil")) {
            val published = content.stencil(catalog.tenant, stencil.resourceId).published
            if (published == null) {
                store.update("DELETE FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND resource_id = ?::uuid", catalog.tenant, draft, stencil.resourceId)
                continue
            }
            val payload = payload(store, catalog.tenant, stencil.hash) as ObjectNode
            val resource = payload.get("resource") as ObjectNode
            resource.set("content", ReferenceWalker.releasedForm(published.content, catalog.catalogKey))
            resource.set("parameterSchema", published.parameterSchema?.takeUnless { it.isNull } ?: mapper.nullNode())
            val hash = store.store(catalog.tenant, "stencil", payload, ReferenceWalker.references(payload))
            changed(store, hash, catalog.tenant, draft, "resource_id = ?::uuid AND parent_id IS NULL", stencil.resourceId)
        }
    }

    /** Points the draft row [where] names at [hash]; a changed content raises its revision, as an edit of a draft row does. */
    private fun changed(store: StorageWrites, hash: String, tenant: String, draft: String, where: String, vararg values: String) {
        store.update(
            """
            UPDATE release_resources
            SET revision = CASE WHEN content_hash = ? THEN revision ELSE revision + 1 END, content_hash = ?, updated_at = NOW()
            WHERE tenant_key = ? AND version_id = ?::uuid AND $where
            """,
            hash,
            hash,
            tenant,
            draft,
            *values,
        )
    }

    private fun payload(store: StorageWrites, tenant: String, hash: String): JsonNode = store.query(
        "SELECT payload::text FROM resource_contents WHERE tenant_key = ? AND content_hash = ?",
        tenant,
        hash,
    ) { mapper.readTree(it.getString(1)) }.single()

    /** Whether drafts [a] and [b] hold the same rows with the same contents. */
    private fun same(store: StorageWrites, tenant: String, a: String, b: String): Boolean = store.query(
        """
        SELECT NOT EXISTS (
            (SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid
             EXCEPT SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid)
            UNION ALL
            (SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid
             EXCEPT SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid))
        """,
        tenant, a, tenant, b, tenant, b, tenant, a,
    ) { it.getBoolean(1) }.single()

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

    companion object {
        /** The name of the draft of what a 1.x catalog last published. */
        const val NAME = "Published in 1.x"

        /** The `app_metadata` key listing the authored 1.x catalogs, `[{tenant, catalog}]`. */
        const val ONE_X_CATALOGS = "upgrade-1x-catalogs"

        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 188484460
    }
}
