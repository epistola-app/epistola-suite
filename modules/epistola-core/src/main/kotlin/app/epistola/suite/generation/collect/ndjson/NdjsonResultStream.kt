// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.ndjson

import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.generation.collect.domain.PartitionAssignment
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/**
 * Streaming NDJSON writer for `/generation/collect`.
 *
 * Format per the v0.3 spec:
 *   - one JSON-encoded `GenerationResult` per line (LF-terminated),
 *   - then a final `{"_meta": true, "hasMore": ..., "count": ..., "lastSequence": ...,
 *     "partitions": {"total": ..., "mine": [...], "hash": "murmur3"}}` line.
 *
 * Compression negotiation is server-driven from `Accept-Encoding`. v0.3 ships
 * `gzip` only — `lz4`/`zstd` may join later. Identity (uncompressed) is the
 * fallback when the client doesn't ask for an encoding we support.
 *
 * Pure helper. Stateless. The controller chooses the encoding via [negotiateEncoding]
 * and hands an [OutputStream] (typically the servlet response stream) to [writeTo].
 */
@Component
class NdjsonResultStream(
    private val objectMapper: ObjectMapper,
) {

    /**
     * Inspect an `Accept-Encoding` header value and pick the best encoding we
     * actually support. Returns one of:
     *   - [Encoding.GZIP] when the client lists `gzip` (or `*`),
     *   - [Encoding.IDENTITY] otherwise (including null/blank header).
     *
     * We don't parse q-values — the spec defaults to gzip when the header is
     * absent, and clients in practice list one of the supported encodings or
     * fall back to identity.
     */
    fun negotiateEncoding(acceptEncoding: String?): Encoding {
        if (acceptEncoding.isNullOrBlank()) return Encoding.GZIP // spec default
        val tokens = acceptEncoding.split(",").map { it.trim().substringBefore(";").trim().lowercase() }
        return when {
            "gzip" in tokens -> Encoding.GZIP
            "*" in tokens -> Encoding.GZIP
            "identity" in tokens -> Encoding.IDENTITY
            else -> Encoding.IDENTITY // unknown only — fall back to identity
        }
    }

    /**
     * Serialize [rows] followed by a `_meta` line into [output], wrapping in
     * gzip if [encoding] is [Encoding.GZIP].
     *
     * The caller is responsible for setting `Content-Type:
     * application/vnd.epistola.v1+ndjson` and (when applicable)
     * `Content-Encoding: gzip` on the response. This class only emits bytes.
     *
     * @param meta computed by the controller from the result of
     *   [FetchGenerationResults][app.epistola.suite.generation.collect.queries.FetchGenerationResults]
     *   and the consumer's current [PartitionAssignment].
     */
    fun writeTo(
        output: OutputStream,
        rows: Iterable<GenerationResultRow>,
        meta: MetaLine,
        encoding: Encoding,
    ) {
        val sink: OutputStream = when (encoding) {
            Encoding.GZIP -> GZIPOutputStream(output)
            Encoding.IDENTITY -> output
        }
        // Serialize to strings ourselves rather than calling
        // `objectMapper.writeValue(writer, ...)` per row — Jackson 3 closes the
        // underlying target after each call by default, which would slam the
        // writer shut after the first row. Serializing to String first and
        // writing through a single managed writer keeps the stream open.
        val writer = OutputStreamWriter(sink, StandardCharsets.UTF_8)
        try {
            for (row in rows) {
                writer.write(objectMapper.writeValueAsString(row.toWireDto()))
                writer.write("\n")
            }
            writer.write(objectMapper.writeValueAsString(meta.toWireMap()))
            writer.write("\n")
            writer.flush()
        } finally {
            // GZIPOutputStream needs `close()` to flush its trailer; plain
            // sink we close too — the caller doesn't expect us to leave it
            // open across return. The underlying response stream is closed by
            // the servlet container regardless.
            sink.close()
        }
    }

    /** What we actually emit per result line — keys mirror the contract `GenerationResult` schema. */
    private fun GenerationResultRow.toWireDto(): Map<String, Any?> = linkedMapOf(
        "sequence" to sequence,
        "requestId" to requestId.value.toString(),
        "batchId" to batchId?.value?.toString(),
        "status" to status.name,
        "documentId" to documentId?.value?.toString(),
        "correlationId" to correlationId,
        "routingKey" to routingKey,
        "templateId" to templateId?.value,
        "variantId" to variantId?.value,
        "versionId" to versionId?.value,
        "filename" to filename,
        "contentType" to contentType,
        "sizeBytes" to sizeBytes,
        "error" to error,
        "completedAt" to completedAt.toString(), // ISO-8601 with offset
    )

    /** Final `_meta` line shape per the spec. */
    private fun MetaLine.toWireMap(): Map<String, Any?> = linkedMapOf(
        "_meta" to true,
        "hasMore" to hasMore,
        "count" to count,
        "lastSequence" to lastSequence,
        "partitions" to linkedMapOf(
            "total" to partitions.total,
            "mine" to partitions.mine,
            "hash" to partitions.hash,
        ),
    )

    enum class Encoding { GZIP, IDENTITY }

    /** What the controller passes to [writeTo] for the trailing `_meta` line. */
    data class MetaLine(
        val hasMore: Boolean,
        val count: Int,
        val lastSequence: Long?,
        val partitions: PartitionAssignment,
    )
}
