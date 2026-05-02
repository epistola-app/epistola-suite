package app.epistola.suite.generation.collect.persistence

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Repository

@Repository
class JdbiGenerationResultRepository(
    private val jdbi: Jdbi,
) : GenerationResultRepository {

    override fun append(row: GenerationResultRow): GenerationResultRow = jdbi.withHandle<GenerationResultRow, Exception> { handle ->
        handle.createQuery(
            """
            INSERT INTO generation_results (
                partition, request_id, batch_id, tenant_key, routing_key, status,
                document_id, correlation_id, template_id, variant_id, version_id,
                filename, content_type, size_bytes, error, completed_at
            )
            VALUES (
                :partition, :requestId, :batchId, :tenantKey, :routingKey, :status,
                :documentId, :correlationId, :templateId, :variantId, :versionId,
                :filename, :contentType, :sizeBytes, :error, :completedAt
            )
            RETURNING sequence, partition, created_at, request_id, batch_id, tenant_key,
                      routing_key, status, document_id, correlation_id, template_id,
                      variant_id, version_id, filename, content_type, size_bytes,
                      error, completed_at
            """,
        )
            .bind("partition", row.partition)
            .bind("requestId", row.requestId.value)
            .bind("batchId", row.batchId?.value)
            .bind("tenantKey", row.tenantKey)
            .bind("routingKey", row.routingKey)
            .bind("status", row.status.name)
            .bind("documentId", row.documentId?.value)
            .bind("correlationId", row.correlationId)
            .bind("templateId", row.templateId)
            .bind("variantId", row.variantId)
            .bind("versionId", row.versionId)
            .bind("filename", row.filename)
            .bind("contentType", row.contentType)
            .bind("sizeBytes", row.sizeBytes)
            .bind("error", row.error)
            .bind("completedAt", row.completedAt)
            .mapTo<GenerationResultRow>()
            .one()
    }

    override fun findFor(
        tenantKey: TenantKey,
        partitions: Set<Int>,
        cursorByPartition: Map<Int, Long>,
        limit: Int,
    ): List<GenerationResultRow> {
        if (partitions.isEmpty() || limit <= 0) return emptyList()

        // Build per-partition cursor arrays so PG can join via UNNEST and prune
        // partitions on `r.partition = c.p`. Cursor defaults to 0 for any
        // partition we haven't acked anything from yet.
        val partitionList = partitions.toIntArray()
        val cursorList = LongArray(partitionList.size) { cursorByPartition[partitionList[it]] ?: 0L }

        return jdbi.withHandle<List<GenerationResultRow>, Exception> { handle ->
            handle.createQuery(
                """
                WITH cursors(p, cur) AS (
                    SELECT unnest(:partitions::int[]), unnest(:cursors::bigint[])
                )
                SELECT r.sequence, r.partition, r.created_at, r.request_id, r.batch_id,
                       r.tenant_key, r.routing_key, r.status, r.document_id,
                       r.correlation_id, r.template_id, r.variant_id, r.version_id,
                       r.filename, r.content_type, r.size_bytes, r.error, r.completed_at
                FROM generation_results r
                JOIN cursors c ON r.partition = c.p
                WHERE r.tenant_key = :tenantKey
                  AND r.sequence > c.cur
                ORDER BY r.sequence
                LIMIT :limit
                """,
            )
                .bind("tenantKey", tenantKey)
                .bindArray("partitions", Int::class.javaObjectType, *partitionList.toTypedArray())
                .bindArray("cursors", Long::class.javaObjectType, *cursorList.toTypedArray())
                .bind("limit", limit)
                .mapTo<GenerationResultRow>()
                .list()
        }
    }
}
