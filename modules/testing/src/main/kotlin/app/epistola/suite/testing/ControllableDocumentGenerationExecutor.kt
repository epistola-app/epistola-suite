package app.epistola.suite.testing

import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.generation.collect.commands.EmitGenerationResult
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.mediator.Mediator
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Test harness for driving the generation lifecycle by hand.
 *
 * Scenario tests disable the production [app.epistola.suite.documents.batch.JobPoller]
 * (via `epistola.generation.polling.enabled=false`) and use this bean to
 * deterministically transition requests through their lifecycle:
 *
 *   - [park] — claim a PENDING request and move it to IN_PROGRESS without doing
 *     any rendering. The result of a `GenerateDocument` command is the same
 *     `DocumentGenerationRequest` you'd hand to [park].
 *   - [complete] — terminate as COMPLETED, write a fake `documents` row, and
 *     dispatch [EmitGenerationResult] so the row appears in
 *     `generation_results` for collectors to pick up.
 *   - [fail] — terminate as FAILED with an error message. Same emit path.
 *   - [cancel] — terminate as CANCELLED. **Does not emit** — cancellation is
 *     not a delivery event per the spec; consumers don't see cancelled jobs.
 *
 * All transitions go through the suite's normal SQL + the real
 * [EmitGenerationResult] command, so the production wiring (cursor advance,
 * partition assignment, NDJSON shape) is exercised end-to-end.
 *
 * Thread-safe — multiple parallel `complete()` calls from a stress scenario
 * are fine. The internal map only tracks parked requests for sanity assertions.
 */
@Component
class ControllableDocumentGenerationExecutor(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Requests that have been parked but not yet terminated. Sanity tracking only. */
    private val parked: MutableMap<GenerationRequestKey, DocumentGenerationRequest> = ConcurrentHashMap()

    /**
     * Move [request] from PENDING to IN_PROGRESS without doing any work.
     * Returns the parked request for fluent chaining.
     *
     * Idempotent — re-parking a request that's already IN_PROGRESS is a no-op.
     */
    fun park(request: DocumentGenerationRequest): DocumentGenerationRequest {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'IN_PROGRESS',
                    claimed_by = :instance,
                    claimed_at = NOW(),
                    started_at = NOW()
                WHERE id = :id AND status IN ('PENDING', 'IN_PROGRESS')
                """,
            )
                .bind("id", request.id)
                .bind("instance", "controllable-executor-test")
                .execute()
        }
        parked[request.id] = request
        return request
    }

    /**
     * Park (if not already parked) and then complete the request as COMPLETED,
     * writing a fake document row and dispatching the emit.
     *
     * @return the [DocumentKey] of the fake document. Use it in assertions —
     *   it's the same id that ends up in `generation_results.document_id`.
     */
    fun complete(
        request: DocumentGenerationRequest,
        filename: String = "fake-${request.id.value}.pdf",
        contentType: String = "application/pdf",
        sizeBytes: Long = 1024L,
    ): DocumentKey {
        park(request)
        val documentId = DocumentKey.of(UUID.randomUUID())
        val now = OffsetDateTime.now()

        jdbi.inTransaction<Unit, Exception> { handle ->
            // Mark the request COMPLETED and link the fake document.
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'COMPLETED',
                    document_key = :documentId,
                    completed_at = NOW()
                WHERE id = :id AND status != 'CANCELLED'
                """,
            )
                .bind("id", request.id)
                .bind("documentId", documentId)
                .execute()

            // Insert a minimal `documents` row — collect doesn't need the full
            // metadata story, but downstream code that joins to `documents`
            // (download endpoints) does.
            handle.createUpdate(
                """
                INSERT INTO documents (
                    id, tenant_key, template_key, variant_key, version_key,
                    filename, correlation_id, content_type, size_bytes,
                    created_at, created_by
                )
                VALUES (
                    :id, :tenantId, :templateId, :variantId, :versionId,
                    :filename, :correlationId, :contentType, :sizeBytes,
                    NOW(), NULL
                )
                """,
            )
                .bind("id", documentId)
                .bind("tenantId", request.tenantKey)
                .bind("templateId", request.templateKey)
                .bind("variantId", request.variantKey)
                .bind("versionId", request.versionKey ?: request.environmentKey)
                .bind("filename", filename)
                .bind("correlationId", request.correlationKey)
                .bind("contentType", contentType)
                .bind("sizeBytes", sizeBytes)
                .execute()
        }

        emitTerminal(
            request = request,
            status = ResultStatus.COMPLETED,
            documentId = documentId,
            sizeBytes = sizeBytes,
            contentType = contentType,
            error = null,
            completedAt = now,
        )
        parked.remove(request.id)
        return documentId
    }

    /** Terminate as FAILED with an error message. Emits a FAILED result. */
    fun fail(request: DocumentGenerationRequest, error: String) {
        park(request)
        val now = OffsetDateTime.now()
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'FAILED',
                    error_message = :error,
                    completed_at = NOW()
                WHERE id = :id AND status != 'CANCELLED'
                """,
            )
                .bind("id", request.id)
                .bind("error", error.take(1000))
                .execute()
        }
        emitTerminal(
            request = request,
            status = ResultStatus.FAILED,
            documentId = null,
            sizeBytes = null,
            contentType = null,
            error = error,
            completedAt = now,
        )
        parked.remove(request.id)
    }

    /**
     * Terminate as CANCELLED. **No emit** — cancelled jobs are not delivered to
     * collectors per the spec (status enum is COMPLETED|FAILED only).
     */
    fun cancel(requestId: GenerationRequestKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'CANCELLED', completed_at = NOW()
                WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED')
                """,
            )
                .bind("id", requestId)
                .execute()
        }
        parked.remove(requestId)
    }

    /** True when a request has been parked but not yet terminated. */
    fun isParked(requestId: GenerationRequestKey): Boolean = parked.containsKey(requestId)

    /**
     * Look up a parked request by id, e.g. when a test wants to re-fetch the
     * full domain object after a `GenerateDocument` returned only the id-shape.
     */
    fun parkedRequest(requestId: GenerationRequestKey): DocumentGenerationRequest? = parked[requestId]

    /**
     * Forget all in-memory state. The DB is independent — call before/after a
     * test if you want to start clean.
     */
    fun reset() {
        parked.clear()
    }

    private fun emitTerminal(
        request: DocumentGenerationRequest,
        status: ResultStatus,
        documentId: DocumentKey?,
        sizeBytes: Long?,
        contentType: String?,
        error: String?,
        completedAt: OffsetDateTime,
    ) {
        try {
            mediator.send(
                EmitGenerationResult(
                    request = request,
                    status = status,
                    documentId = documentId,
                    sizeBytes = sizeBytes,
                    contentType = contentType,
                    error = error,
                    completedAt = completedAt,
                ),
            )
        } catch (e: Exception) {
            // Test harness — surface the failure loudly rather than silently
            // dropping the emit (production code logs and moves on; in tests
            // we want to know).
            logger.error("ControllableExecutor emit failed for request {}: {}", request.id.value, e.message, e)
            throw e
        }
    }

    /**
     * Re-fetch a request from the DB. Useful when a test only has the id.
     */
    fun fetchRequest(requestId: GenerationRequestKey): DocumentGenerationRequest? = jdbi.withHandle<DocumentGenerationRequest?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, batch_id, tenant_key, catalog_key, template_key, variant_key, version_key,
                   environment_key, data, filename, correlation_key, routing_key, document_key,
                   status, claimed_by, claimed_at, error_message, created_at, started_at,
                   completed_at, expires_at
            FROM document_generation_requests
            WHERE id = :id
            """,
        )
            .bind("id", requestId)
            .mapTo<DocumentGenerationRequest>()
            .findOne()
            .orElse(null)
    }
}
