// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.types.ContentLayout
import app.epistola.suite.catalog.types.ResourceTypes
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Reads the resources of a catalog version from its rows and their contents (ADR 0038, ADR 0039),
 * whatever their type: each row's content is the resource's released form in its envelope, and a
 * type stored as parts ([ContentLayout.Parts]) is assembled from its part rows. Draft and release
 * alike, so what a draft exports is what its release would hold.
 */
@Component
class VersionResourceReader(
    private val types: ResourceTypes,
    private val objectMapper: ObjectMapper,
) {
    /** One resource of a version: its row's columns and its wire form. */
    data class VersionResource(val type: String, val key: String, val name: String, val description: String?, val resource: JsonNode) {
        /** How catalog content keys a resource: `"type/key"`. */
        val contentKey: String get() = "$type/$key"
    }

    /**
     * Every resource of the version [catalog]'s callers mean ([versionAt]: the targeted version,
     * else the current one), by type and key, each in its released form as stored.
     */
    fun read(handle: Handle, catalog: CatalogId): List<VersionResource> {
        val rows = handle.createQuery(
            """
            SELECT r.resource_type, r.resource_key, r.resource_id, r.version_id, r.name, r.description, c.payload::text AS payload
            FROM release_wire_resources r
            JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
            WHERE r.tenant_key = :t AND r.version_id = ${versionAt("t", "c")}
              AND r.resource_type IN (<types>)
            ORDER BY r.resource_type, r.resource_key
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bindList("types", types.names.toList())
            .map { rs, _ ->
                Row(
                    type = rs.getString("resource_type"),
                    key = rs.getString("resource_key"),
                    resourceId = rs.getObject("resource_id", UUID::class.java),
                    versionId = rs.getObject("version_id", UUID::class.java),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    payload = objectMapper.readTree(rs.getString("payload")),
                )
            }
            .list()
        val parts = partsOf(handle, catalog, rows)
        return rows.map { row ->
            val resource = when (val layout = types.of(row.type).layout) {
                ContentLayout.Single -> StoredContent.resourceOf(row.payload)

                is ContentLayout.Parts -> parts[row.resourceId]?.takeIf { it.isNotEmpty() }
                    ?.let { layout.assemble(row.payload, it) }
                    ?: StoredContent.resourceOf(row.payload)
            }
            VersionResource(row.type, row.key, row.name, row.description, resource.deepCopy())
        }
    }

    /** [resource] bound to its type's contract class. */
    fun bind(resource: VersionResource): CatalogResource = objectMapper.treeToValue(resource.resource, types.of(resource.type).wireClass)

    /** The stored part contents of every row of [rows] whose type has parts, by the row's resource id, then by part key. */
    private fun partsOf(handle: Handle, catalog: CatalogId, rows: List<Row>): Map<UUID, Map<String, JsonNode>> {
        val parents = rows.filter { types.of(it.type).layout is ContentLayout.Parts }
        if (parents.isEmpty()) return emptyMap()
        val partTypes = parents.map { (types.of(it.type).layout as ContentLayout.Parts).partType }.distinct()
        return handle.createQuery(
            """
            SELECT p.parent_id, p.resource_key, c.payload::text AS payload
            FROM release_resources p
            JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
            WHERE p.tenant_key = :t AND p.version_id = :versionId
              AND p.parent_id IN (<parents>) AND p.resource_type IN (<partTypes>)
            """,
        )
            .bind("t", catalog.tenantKey)
            .bind("versionId", parents.first().versionId)
            .bindList("parents", parents.map { it.resourceId })
            .bindList("partTypes", partTypes)
            .map { rs, _ -> Triple(rs.getObject("parent_id", UUID::class.java), rs.getString("resource_key"), objectMapper.readTree(rs.getString("payload"))) }
            .list()
            .groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, list) -> list.toMap() }
    }

    private data class Row(
        val type: String,
        val key: String,
        val resourceId: UUID,
        val versionId: UUID,
        val name: String,
        val description: String?,
        val payload: JsonNode,
    )
}
