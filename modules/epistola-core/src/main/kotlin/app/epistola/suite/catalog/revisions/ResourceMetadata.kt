// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** Producer-specific data by reverse-DNS namespace (contract `CatalogResource.metadata`). */
typealias ProducerMetadata = Map<String, Map<String, Any?>>

/**
 * A resource's producer metadata (contract `2.0.0-alpha.3`, `CatalogResource.metadata`) through its
 * stores. The suite never writes metadata of its own and counts it in no fingerprint: it is kept as a
 * received catalog gave it, so installing or importing a resource sets it ([explicit] metadata, an
 * empty map clearing it), and every other write (an author's edit) keeps what the draft holds.
 */
object ResourceMetadata {

    /**
     * [payload] (a stored content, `{ "resource": … }`) with its resource's `metadata` set: [explicit]
     * when given (empty: none), else what the targeted draft's row [type] [key] of [catalog] holds now
     * ([held]).
     */
    fun carried(handle: Handle, catalog: CatalogId, type: String, key: String, payload: JsonNode, explicit: ProducerMetadata?, objectMapper: ObjectMapper): JsonNode = with(payload, if (explicit != null) explicit.takeIf { it.isNotEmpty() }?.let { objectMapper.valueToTree<JsonNode>(it) } else held(handle, catalog, type, key))

    /** [payload] with its resource's `metadata` [metadata], or none when null or empty. */
    fun with(payload: JsonNode, metadata: JsonNode?): JsonNode {
        val copy = payload.deepCopy() as ObjectNode
        val resource = copy.get("resource") as? ObjectNode ?: return copy
        if (metadata != null && metadata.isObject && !metadata.isEmpty) resource.set("metadata", metadata) else resource.remove("metadata")
        return copy
    }

    /**
     * What the targeted draft's row [type] [key] of [catalog] holds, as explicit metadata for a write
     * that moves the resource to another address (empty: none), so the move keeps it.
     */
    fun moving(handle: Handle, catalog: CatalogId, type: String, key: String): ProducerMetadata = held(handle, catalog, type, key)
        ?.let { MAPPER.readValue(it.toString(), PRODUCER_METADATA) }
        .orEmpty()

    /** The metadata the targeted draft's row [type] [key] of [catalog] holds; null when none or no row. */
    fun held(handle: Handle, catalog: CatalogId, type: String, key: String): JsonNode? = DraftResources.metadataOf(handle, catalog, type, key)
        ?.let { MAPPER.readTree(it) }
        ?.takeIf { it.isObject && !it.isEmpty }

    private val MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()

    private val PRODUCER_METADATA = object : tools.jackson.core.type.TypeReference<ProducerMetadata>() {}
}
