package app.epistola.suite.generation.collect.domain

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import java.time.OffsetDateTime

/**
 * One row of `generation_results` — a terminal generation result that's waiting to be
 * (or has been) delivered to a consumer through `/generation/collect`. Values mirror
 * the contract's `GenerationResult` schema closely so the row can be serialized to
 * NDJSON with minimal mapping.
 *
 * `sequence` is assigned by the database (BIGSERIAL) on insert. Construct rows for
 * insertion with `sequence = 0L` and let the repository ignore the field; the value
 * is overwritten on read.
 *
 * `partition` is computed by the emitter via [Partition.partitionFor] from
 * `routingKey`. `routingKey` itself is whatever the client sent on the original
 * generation request, falling back to the request id when null.
 */
data class GenerationResultRow(
    val sequence: Long,
    val partition: Int,
    val createdAt: OffsetDateTime,

    val requestId: GenerationRequestKey,
    val batchId: BatchKey?,
    val tenantKey: TenantKey,
    val routingKey: String,
    val status: ResultStatus,

    val documentId: DocumentKey?,
    val correlationId: String?,
    val templateId: TemplateKey?,
    val variantId: VariantKey?,
    val versionId: VersionKey?,
    val filename: String?,
    val contentType: String?,
    val sizeBytes: Long?,
    val error: String?,
    val completedAt: OffsetDateTime,
)

/** Terminal status at the time the result was emitted. Mirrors the contract enum. */
enum class ResultStatus { COMPLETED, FAILED }
