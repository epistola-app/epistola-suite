// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.sql.Connection

/**
 * What a 1.x catalog last published becomes a draft of its own (ADR 0035, the 2.0 upgrade): no
 * release is cut. Every authored 1.x catalog keeps its next draft, the working copy with any
 * unpublished 1.x work, and gets beside it a named draft **Published in 1.x** holding what each of
 * its templates and stencils last published (`catalog_upgrade_published`), which its author releases
 * and deploys to restore what was live. The other types were never versioned in 1.x: their draft is
 * what was published, so the named draft shares their contents.
 *
 * The named draft starts as a copy of the next draft (`copy_catalog_version_content`), then:
 * - a template whose default variant was never published is left out, and so is a variant never
 *   published; each published variant's model replaces the draft's, in its released form
 *   ([V20261025170700__core_templates_to_storage.releasedModel]); a published data contract replaces
 *   the draft's data model and examples, each example by its key ([examples]);
 * - a stencil never published is left out; a published one's content and parameter schema replace the
 *   draft's, in released form ([V20261025083103__core_stencils_to_storage.releasedForm]);
 * - every part of it is marked ready: it was published.
 * When the result holds what the next draft holds, the named draft is removed: there is nothing
 * of its own to release.
 *
 * Both drafts name as their base the catalog's highest 1.x release by number (`continues_after`),
 * which is not here: a release of either is numbered above it. Both declare, by name and pinning
 * nothing, every catalog their content references (1.x had no declarations): releasing pins each to
 * its catalog's highest release. A subscribed 1.x catalog gets its release from its source at boot.
 * The authored 1.x catalogs are listed in `app_metadata` ([ONE_X_CATALOGS]), so the tenant home asks
 * for each to be released and deployed while it has no release. The two tables of the upgrade then go.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only, plus the frozen conversions of
 * `V20261025170700` and `V20261025083103` (this package, checksums pinned).
 */
@Suppress("ClassName")
class V20261027112414__core_published_in_1x : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    private data class Queued(val tenant: String, val catalogId: String, val catalogKey: String, val next: String, val continuesAfter: String?)

    override fun migrate(context: Context) {
        val connection = context.connection
        val queued = connection.prepareStatement(
            """
            SELECT q.tenant_key, q.catalog_id::text, c.catalog_key::text, c.next_version_id::text, q.continues_after
            FROM catalog_upgrade_queue q
            JOIN catalogs c ON c.tenant_key = q.tenant_key AND c.catalog_id = q.catalog_id
            WHERE c.type = 'AUTHORED' AND c.next_version_id IS NOT NULL
            ORDER BY q.tenant_key, c.catalog_key
            """,
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(Queued(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))) } } }
        var created = 0
        for (catalog in queued) {
            if (catalog.continuesAfter != null) setBase(connection, catalog.tenant, catalog.next, catalog.continuesAfter)
            val published = publishedDraft(connection, catalog)
            if (published != null) created++
            listOfNotNull(catalog.next, published).forEach { declareUsed(connection, catalog, it) }
        }
        // The authored 1.x catalogs, which the tenant home asks to be released and deployed until they are.
        exec(
            connection,
            """
            INSERT INTO app_metadata (key, value)
            SELECT ?, jsonb_agg(jsonb_build_object('tenant', q.tenant_key, 'catalog', q.catalog_id::text) ORDER BY q.tenant_key, q.catalog_id)
            FROM catalog_upgrade_queue q JOIN catalogs c ON c.tenant_key = q.tenant_key AND c.catalog_id = q.catalog_id
            WHERE c.type = 'AUTHORED'
            HAVING count(*) > 0
            """,
            ONE_X_CATALOGS,
        )
        connection.createStatement().use {
            it.execute("DROP TABLE catalog_upgrade_published")
            it.execute("DROP TABLE catalog_upgrade_queue")
        }
        if (queued.isNotEmpty()) log.info("2.0 upgrade: {} of {} authored catalog(s) have a 'Published in 1.x' draft to release", created, queued.size)
    }

    private fun setBase(connection: Connection, tenant: String, versionId: String, base: String) {
        connection.prepareStatement("UPDATE catalog_versions SET base_version = ? WHERE tenant_key = ? AND version_id = ?::uuid AND base_version IS NULL AND base_version_id IS NULL").use {
            it.setString(1, base)
            it.setString(2, tenant)
            it.setString(3, versionId)
            it.executeUpdate()
        }
    }

    /** The named draft of what [catalog] published; null when it would hold what its next draft holds. */
    private fun publishedDraft(connection: Connection, catalog: Queued): String? {
        val versionId = connection.prepareStatement(
            """
            INSERT INTO catalog_versions (tenant_key, catalog_id, state, name, base_version, default_theme_resource_id, presets)
            SELECT tenant_key, catalog_id, 'draft', ?, ?, default_theme_resource_id, presets
            FROM catalog_versions WHERE tenant_key = ? AND version_id = ?::uuid
            RETURNING version_id::text
            """,
        ).use {
            it.setString(1, NAME)
            it.setString(2, catalog.continuesAfter)
            it.setString(3, catalog.tenant)
            it.setString(4, catalog.next)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
        connection.prepareStatement("SELECT copy_catalog_version_content(?, ?::uuid, ?::uuid)").use {
            it.setString(1, catalog.tenant)
            it.setString(2, catalog.next)
            it.setString(3, versionId)
            it.executeQuery().close()
        }
        templates(connection, catalog, versionId)
        stencils(connection, catalog, versionId)
        if (same(connection, catalog.tenant, catalog.next, versionId)) {
            exec(connection, "DELETE FROM catalog_versions WHERE tenant_key = ? AND version_id = ?::uuid", catalog.tenant, versionId)
            return null
        }
        // It was published: every part of it ready, as it is now.
        exec(connection, "DELETE FROM resource_ready_marks WHERE tenant_key = ? AND version_id = ?::uuid", catalog.tenant, versionId)
        exec(
            connection,
            """
            INSERT INTO resource_ready_marks (tenant_key, version_id, resource_id, part, digest)
            SELECT d.tenant_key, d.version_id, d.resource_id, d.part, d.digest
            FROM resource_content_digests d WHERE d.tenant_key = ? AND d.version_id = ?::uuid
            """,
            catalog.tenant,
            versionId,
        )
        return versionId
    }

    private data class Row(val resourceId: String, val key: String, val hash: String)

    private fun rows(connection: Connection, tenant: String, versionId: String, type: String): List<Row> = connection.prepareStatement(
        "SELECT resource_id::text, resource_key, content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND resource_type = ? AND parent_id IS NULL ORDER BY resource_key",
    ).use { st ->
        st.setString(1, tenant)
        st.setString(2, versionId)
        st.setString(3, type)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(Row(rs.getString(1), rs.getString(2), rs.getString(3))) } }
    }

    private fun templates(connection: Connection, catalog: Queued, versionId: String) {
        for (template in rows(connection, catalog.tenant, versionId, "template")) {
            val publishedModels = published(connection, catalog.tenant, template.resourceId, "variant")
            val head = payload(connection, catalog.tenant, template.hash) as ObjectNode
            val resource = head.get("resource") as ObjectNode
            val entries = (resource.get("variants") as? ArrayNode)?.toList().orEmpty()
            val default = entries.firstOrNull { it.get("isDefault")?.asBoolean() == true } ?: entries.firstOrNull()
            val defaultKey = default?.get("key")?.asString()
            if (defaultKey == null || defaultKey !in publishedModels) {
                // Before 2.0 a template rendered through its default variant: never published, it was not live.
                exec(connection, "DELETE FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND (resource_id = ?::uuid OR parent_id = ?::uuid)", catalog.tenant, versionId, template.resourceId, template.resourceId)
                continue
            }
            for (variant in variantRows(connection, catalog.tenant, versionId, template.resourceId)) {
                val model = publishedModels[variant.key]
                if (model == null) {
                    exec(connection, "DELETE FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND parent_id = ?::uuid AND resource_key = ?", catalog.tenant, versionId, template.resourceId, variant.key)
                    continue
                }
                val part = payload(connection, catalog.tenant, variant.hash) as ObjectNode
                (part.get("resource") as ObjectNode).set("templateModel", V20261025170700__core_templates_to_storage.releasedModel(model.get("templateModel") ?: model, catalog.catalogKey))
                val hash = store(connection, catalog.tenant, "variant", part)
                V20261025170700__core_templates_to_storage.references(part).forEach { storeReference(connection, catalog.tenant, hash, it.alias, it.type, it.key) }
                exec(connection, "UPDATE release_resources SET content_hash = ? WHERE tenant_key = ? AND version_id = ?::uuid AND parent_id = ?::uuid AND resource_key = ?", hash, catalog.tenant, versionId, template.resourceId, variant.key)
            }
            resource.set("variants", mapper.createArrayNode().also { kept -> entries.filter { it.get("key")?.asString() in publishedModels }.forEach { kept.add(it) } })
            published(connection, catalog.tenant, template.resourceId, "contract")[""]?.let { contract ->
                resource.set("dataModel", contract.get("dataModel")?.takeUnless { it.isNull } ?: mapper.nullNode())
                resource.set("dataExamples", examples(contract.get("dataExamples")))
            }
            val hash = store(connection, catalog.tenant, "template", head)
            copyReferences(connection, catalog.tenant, template.hash, hash)
            exec(connection, "UPDATE release_resources SET content_hash = ? WHERE tenant_key = ? AND version_id = ?::uuid AND resource_id = ?::uuid AND parent_id IS NULL", hash, catalog.tenant, versionId, template.resourceId)
        }
    }

    private fun variantRows(connection: Connection, tenant: String, versionId: String, templateId: String): List<Row> = connection.prepareStatement(
        "SELECT resource_id::text, resource_key, content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND parent_id = ?::uuid ORDER BY resource_key",
    ).use { st ->
        st.setString(1, tenant)
        st.setString(2, versionId)
        st.setString(3, templateId)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(Row(rs.getString(1), rs.getString(2), rs.getString(3))) } }
    }

    private fun stencils(connection: Connection, catalog: Queued, versionId: String) {
        for (stencil in rows(connection, catalog.tenant, versionId, "stencil")) {
            val version = published(connection, catalog.tenant, stencil.resourceId, "stencil")[""]
            if (version == null) {
                exec(connection, "DELETE FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid AND resource_id = ?::uuid", catalog.tenant, versionId, stencil.resourceId)
                continue
            }
            val content = payload(connection, catalog.tenant, stencil.hash) as ObjectNode
            val resource = content.get("resource") as ObjectNode
            resource.set("content", V20261025083103__core_stencils_to_storage.releasedForm(version.get("content"), catalog.catalogKey))
            resource.set("parameterSchema", version.get("parameterSchema")?.takeUnless { it.isNull } ?: mapper.nullNode())
            val hash = store(connection, catalog.tenant, "stencil", content)
            V20261025083103__core_stencils_to_storage.references(content).forEach { storeReference(connection, catalog.tenant, hash, it.alias, it.type, it.key) }
            exec(connection, "UPDATE release_resources SET content_hash = ? WHERE tenant_key = ? AND version_id = ?::uuid AND resource_id = ?::uuid AND parent_id IS NULL", hash, catalog.tenant, versionId, stencil.resourceId)
        }
    }

    /** What [resourceId] last published of [kind] in 1.x, by part (`""` for a contract or stencil). */
    private fun published(connection: Connection, tenant: String, resourceId: String, kind: String): Map<String, JsonNode> = connection.prepareStatement(
        "SELECT part, payload::text FROM catalog_upgrade_published WHERE tenant_key = ? AND resource_id = ?::uuid AND kind = ?",
    ).use { st ->
        st.setString(1, tenant)
        st.setString(2, resourceId)
        st.setString(3, kind)
        st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), mapper.readTree(rs.getString(2))) } }
    }

    private fun payload(connection: Connection, tenant: String, hash: String): JsonNode = connection.prepareStatement(
        "SELECT payload::text FROM resource_contents WHERE tenant_key = ? AND content_hash = ?",
    ).use { st ->
        st.setString(1, tenant)
        st.setString(2, hash)
        st.executeQuery().use { rs ->
            check(rs.next()) { "Content $hash of tenant $tenant is not stored" }
            mapper.readTree(rs.getString(1))
        }
    }

    /** Stores [payload] by its content hash (`ResourceContentStore` of this time). @return the hash. */
    private fun store(connection: Connection, tenant: String, kind: String, payload: JsonNode): String {
        val canonical = V20261025170700__core_templates_to_storage.canonical(payload)
        val hash = V20261025170700__core_templates_to_storage.contentHash(canonical)
        connection.prepareStatement(
            "INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, ?, ?::jsonb) ON CONFLICT (tenant_key, content_hash) DO NOTHING",
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, kind)
            it.setString(4, canonical)
            it.executeUpdate()
        }
        return hash
    }

    private fun storeReference(connection: Connection, tenant: String, hash: String, alias: String?, type: String, key: String) {
        connection.prepareStatement(
            "INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, alias)
            it.setString(4, type)
            it.setString(5, key)
            it.executeUpdate()
        }
    }

    /** A template's own references (its theme) are not in what changed: they carry over. */
    private fun copyReferences(connection: Connection, tenant: String, from: String, to: String) {
        if (from == to) return
        exec(
            connection,
            """
            INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key)
            SELECT tenant_key, ?, alias, resource_type, resource_key FROM resource_references WHERE tenant_key = ? AND content_hash = ?
            ON CONFLICT DO NOTHING
            """,
            to,
            tenant,
            from,
        )
    }

    /** Whether [a] and [b] hold the same rows with the same contents. */
    private fun same(connection: Connection, tenant: String, a: String, b: String): Boolean = connection.prepareStatement(
        """
        SELECT NOT EXISTS (
            (SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid
             EXCEPT SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid)
            UNION ALL
            (SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid
             EXCEPT SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = ? AND version_id = ?::uuid))
        """,
    ).use { st ->
        listOf(tenant, a, tenant, b, tenant, b, tenant, a).forEachIndexed { i, v -> st.setString(i + 1, v) }
        st.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

    /**
     * Declares, by name and pinning nothing, every other catalog of the tenant the content of
     * [versionId] references, by the alias its references name (a catalog's key: 1.x had no
     * declarations) and by a variant attribute's qualifier (`system.locale` names `system`). An alias
     * no catalog of the tenant has, or two share, is left: releasing refuses what it cannot resolve.
     */
    private fun declareUsed(connection: Connection, catalog: Queued, versionId: String) {
        val aliases = sortedSetOf<String>()
        connection.prepareStatement(
            """
            SELECT DISTINCT rr.alias FROM release_resources r
            JOIN resource_references rr ON rr.tenant_key = r.tenant_key AND rr.content_hash = r.content_hash
            WHERE r.tenant_key = ? AND r.version_id = ?::uuid AND rr.alias IS NOT NULL
            """,
        ).use { st ->
            st.setString(1, catalog.tenant)
            st.setString(2, versionId)
            st.executeQuery().use { rs -> while (rs.next()) aliases += rs.getString(1) }
        }
        connection.prepareStatement(
            """
            SELECT c.payload::text FROM release_resources r
            JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
            WHERE r.tenant_key = ? AND r.version_id = ?::uuid AND r.resource_type = 'template' AND r.parent_id IS NULL
            """,
        ).use { st ->
            st.setString(1, catalog.tenant)
            st.setString(2, versionId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    mapper.readTree(rs.getString(1)).path("resource").path("variants").forEach { variant ->
                        variant.path("attributes").propertyNames().forEach { key -> key.substringBefore('.', "").takeIf { it.isNotEmpty() }?.let { aliases += it } }
                    }
                }
            }
        }
        aliases.remove(catalog.catalogKey)
        var position = connection.prepareStatement("SELECT COALESCE(MAX(position) + 1, 0) FROM catalog_version_dependencies WHERE tenant_key = ? AND version_id = ?::uuid").use { st ->
            st.setString(1, catalog.tenant)
            st.setString(2, versionId)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        for (alias in aliases) {
            val target = connection.prepareStatement("SELECT registry, namespace FROM catalogs WHERE tenant_key = ? AND catalog_key = ?").use { st ->
                st.setString(1, catalog.tenant)
                st.setString(2, alias)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
            }.singleOrNull() ?: continue
            val inserted = connection.prepareStatement(
                """
                INSERT INTO catalog_version_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias, position)
                SELECT ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM catalog_version_dependencies WHERE tenant_key = ? AND version_id = ?::uuid AND alias = ?)
                """,
            ).use {
                it.setString(1, catalog.tenant)
                it.setString(2, catalog.catalogId)
                it.setString(3, versionId)
                it.setString(4, target.first)
                it.setString(5, target.second)
                it.setString(6, alias)
                it.setString(7, alias)
                it.setInt(8, position)
                it.setString(9, catalog.tenant)
                it.setString(10, versionId)
                it.setString(11, alias)
                it.executeUpdate()
            }
            if (inserted > 0) position++
        }
    }

    private fun exec(connection: Connection, sql: String, vararg values: String) {
        connection.prepareStatement(sql).use { st ->
            values.forEachIndexed { i, v -> st.setString(i + 1, v) }
            st.executeUpdate()
        }
    }

    companion object {
        /** The name of the draft of what a 1.x catalog last published. */
        const val NAME = "Published in 1.x"

        /** The `app_metadata` key listing the authored 1.x catalogs, `[{tenant, catalog}]`. */
        const val ONE_X_CATALOGS = "upgrade-1x-catalogs"

        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = -1683686171

        private val mapper = JsonMapper.builder().build()

        /** A data contract's examples as the wire form keeps them: `name`, `data` and `key`, each with a key (`V20261025170700` of this time). */
        fun examples(json: JsonNode?): ArrayNode {
            val examples = json?.takeIf { it is ArrayNode }?.toList().orEmpty()
            val taken = examples.mapNotNull { it.text("key") }.toMutableSet()
            return mapper.createArrayNode().also { out ->
                examples.forEach { example ->
                    val name = example.text("name") ?: "Unnamed"
                    val key = example.text("key") ?: V20261025170700__core_templates_to_storage.dataExampleKey(name, taken).also { taken += it }
                    out.add(
                        mapper.createObjectNode().apply {
                            put("name", name)
                            set("data", example.get("data") ?: mapper.createObjectNode())
                            put("key", key)
                        },
                    )
                }
            }
        }

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
    }
}
