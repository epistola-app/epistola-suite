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

    /**
     * What the version [catalog]'s callers mean keeps beside each resource's released form (ADR 0033:
     * the envelope's other members, a code list's `source`), by content key; resources with none are
     * left out. A release stores the content its draft does, so it retains these with it.
     */
    fun settings(handle: Handle, catalog: CatalogId): Map<String, tools.jackson.databind.node.ObjectNode> = handle.createQuery(
        """
        SELECT r.resource_type, r.resource_key, (c.payload - 'resource')::text AS settings
        FROM release_wire_resources r
        JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
        WHERE r.tenant_key = :t AND r.version_id = ${versionAt("t", "c")}
          AND r.resource_type IN (<types>) AND c.payload - 'resource' <> '{}'::jsonb
        """,
    )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bindList("types", types.names.toList())
        .map { rs, _ -> "${rs.getString("resource_type")}/${rs.getString("resource_key")}" to objectMapper.readTree(rs.getString("settings")) as tools.jackson.databind.node.ObjectNode }
        .list()
        .toMap()

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

    /**
     * One reference a resource of a current version holds (`resource_references`, ADR 0033): the
     * resource by its catalog's address (`namespace/key`), type and key (a template's for what one of
     * its parts holds), and what it names: the [alias] of a catalog (null for its own) and the type
     * and key there.
     */
    data class HeldReference(
        val catalogAddress: String,
        val type: String,
        val key: String,
        val alias: String?,
        val referencedType: String,
        val referencedKey: String,
    )

    /**
     * Every reference held by a resource of the version each catalog of [tenantKey] means (the
     * targeted version, else the current one), parts included, as stored contents index them: what
     * uses what, for the resource graph.
     */
    fun currentReferences(handle: Handle, tenantKey: app.epistola.suite.common.ids.TenantKey): List<HeldReference> = handle.createQuery(
        """
        SELECT DISTINCT c.namespace || '/' || c.catalog_key AS catalog_address,
               COALESCE(parent.resource_type, r.resource_type) AS resource_type,
               COALESCE(parent.resource_key, r.resource_key) AS resource_key,
               rr.alias, rr.resource_type AS referenced_type, rr.resource_key AS referenced_key
        FROM release_resources r
        JOIN catalog_current_versions cv ON cv.tenant_key = r.tenant_key AND cv.version_id = r.version_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
        JOIN resource_references rr ON rr.tenant_key = r.tenant_key AND rr.content_hash = r.content_hash
        LEFT JOIN release_resources parent ON parent.tenant_key = r.tenant_key AND parent.version_id = r.version_id
             AND parent.resource_id = r.parent_id AND parent.parent_id IS NULL
        WHERE r.tenant_key = :t
        """,
    )
        .bind("t", tenantKey)
        .map { rs, _ ->
            HeldReference(
                rs.getString("catalog_address"),
                rs.getString("resource_type"),
                rs.getString("resource_key"),
                rs.getString("alias"),
                rs.getString("referenced_type"),
                rs.getString("referenced_key"),
            )
        }
        .list()
}
