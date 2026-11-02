// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.releases.ContentVersion
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
     * when given (empty: none), else what version [at]'s row [type] [key] holds now
     * ([held]).
     */
    fun carried(handle: Handle, at: ContentVersion, type: String, key: String, payload: JsonNode, explicit: ProducerMetadata?, objectMapper: ObjectMapper): JsonNode = with(payload, if (explicit != null) explicit.takeIf { it.isNotEmpty() }?.let { objectMapper.valueToTree<JsonNode>(it) } else held(handle, at, type, key))

    /** [payload] with its resource's `metadata` [metadata], or none when null or empty. */
    fun with(payload: JsonNode, metadata: JsonNode?): JsonNode {
        val copy = payload.deepCopy() as ObjectNode
        val resource = copy.get("resource") as? ObjectNode ?: return copy
        if (metadata != null && metadata.isObject && !metadata.isEmpty) resource.set("metadata", metadata) else resource.remove("metadata")
        return copy
    }

    /** The metadata version [at]'s row [type] [key] holds; null when none or no row. */
    fun held(handle: Handle, at: ContentVersion, type: String, key: String): JsonNode? = DraftResources.metadataOf(handle, at, type, key)
        ?.let { MAPPER.readTree(it) }
        ?.takeIf { it.isObject && !it.isEmpty }

    private val MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()
}
