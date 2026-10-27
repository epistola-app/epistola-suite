// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core.frozen

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/**
 * How the 2.0 upgrade hashes what it stores (ADR 0033, ADR 0035), frozen: content is stored under the
 * SHA-256 of its canonical JSON, every object's keys sorted at any depth, as `ResourceContentStore`
 * of 2.0 takes it; a file is stored under the SHA-256 of its bytes. Golden vectors in
 * `fixtures/storage/v1.json` pin both (`FrozenToolkitGoldenVectorsTest`).
 */
object CanonicalJson {

    /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
    const val CHECKSUM: Int = -737429473

    val mapper: JsonMapper = JsonMapper.builder().build()

    /** The JSON the content hash is taken over: every object's keys sorted, at any depth. */
    fun canonical(payload: JsonNode): String = mapper.writeValueAsString(sortKeys(payload))

    /** SHA-256 of [canonical], lowercase hex. */
    fun contentHash(canonical: String): String = sha256Hex(canonical.toByteArray(Charsets.UTF_8))

    /** SHA-256 of [bytes], lowercase hex: what a file's bytes are stored under. */
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sortKeys(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> mapper.createObjectNode().also { sorted ->
            node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
        }

        is ArrayNode -> mapper.createArrayNode().also { sorted -> node.forEach { sorted.add(sortKeys(it)) } }

        else -> node
    }
}
