// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
    /** The catalog release rendered; null for results of requests accepted before 2.0. */
    val releaseVersion: String? = null,
    /** The draft rendered, live, through an environment that follows it; null for a release. */
    val draftId: String? = null,
    /** The draft's name when it was rendered. */
    val draftLabel: String? = null,
    /** The catalog of the template rendered, copied from the request; null for results emitted before it was stored. */
    val registry: String? = null,
    val namespace: String? = null,
    val catalogKey: String? = null,
    /** The id of the catalog version rendered, a release's or a draft's (contract `versionId`). */
    val catalogVersionId: String? = null,
    val filename: String?,
    val contentType: String?,
    val sizeBytes: Long?,
    val error: String?,
    val completedAt: OffsetDateTime,
)

/** Terminal status at the time the result was emitted. Mirrors the contract enum. */
enum class ResultStatus { COMPLETED, FAILED }
