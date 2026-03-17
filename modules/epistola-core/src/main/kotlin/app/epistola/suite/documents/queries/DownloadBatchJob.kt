package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.model.AssemblyStatus
import app.epistola.suite.documents.model.BatchDownloadFormat
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import app.epistola.suite.storage.StoredContent
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class DownloadResult(
    val content: StoredContent,
    val filename: String,
    val contentType: String,
)

class BatchDownloadException(
    val statusCode: Int,
    override val message: String,
) : RuntimeException(message) {
    companion object {
        fun badRequest(message: String) = BatchDownloadException(400, message)
        fun notFound(message: String) = BatchDownloadException(404, message)
        fun conflict(message: String) = BatchDownloadException(409, message)
    }
}

/**
 * Query to download an assembled batch output file.
 *
 * @property tenantKey Tenant that owns the batch
 * @property batchKey The batch ID
 * @property format The download format (ZIP or MERGED_PDF)
 * @property part The part number (1-based)
 */
data class DownloadBatchJob(
    val tenantKey: TenantKey,
    val batchKey: BatchKey,
    val format: String,
    val part: Int,
) : Query<DownloadResult>

@Component
class DownloadBatchJobHandler(
    private val jdbi: Jdbi,
    private val contentStore: ContentStore,
) : QueryHandler<DownloadBatchJob, DownloadResult> {

    override fun handle(query: DownloadBatchJob): DownloadResult {
        val downloadFormat = try {
            BatchDownloadFormat.valueOf(query.format.uppercase())
        } catch (_: IllegalArgumentException) {
            throw BatchDownloadException.badRequest("Invalid format: ${query.format}")
        }

        val batch = getBatch(query.tenantKey, query.batchKey)
            ?: throw BatchDownloadException.notFound("Batch not found")

        // Single-document optimization: if batch has exactly 1 completed document, return it directly
        if (batch.totalCount == 1 && batch.completedCount == 1) {
            val singleDoc = getSingleCompletedDocument(query.batchKey)
            if (singleDoc != null) {
                val stored = contentStore.get(ContentKey.document(query.tenantKey, singleDoc.documentKey))
                    ?: throw BatchDownloadException.notFound("Document content not found")
                return DownloadResult(
                    content = stored,
                    filename = singleDoc.filename ?: "document.pdf",
                    contentType = "application/pdf",
                )
            }
        }

        // Validate batch state
        if (batch.completedAt == null) {
            throw BatchDownloadException.conflict("Batch is not yet complete")
        }
        if (batch.failedCount > 0) {
            throw BatchDownloadException.conflict("Batch has ${batch.failedCount} failed items")
        }
        if (batch.downloadFormats.isEmpty()) {
            throw BatchDownloadException.badRequest("No download formats were requested at submission time")
        }
        if (!batch.downloadFormats.contains(downloadFormat)) {
            throw BatchDownloadException.badRequest("Format ${query.format} was not requested at submission time")
        }
        if (batch.assemblyStatus != AssemblyStatus.COMPLETED) {
            throw BatchDownloadException.conflict("Assembly status is ${batch.assemblyStatus}")
        }

        // Validate part number
        val parts = batch.partsForFormat(downloadFormat)
        if (query.part < 1 || query.part > parts) {
            throw BatchDownloadException.notFound("Part ${query.part} not found (available: 1-$parts)")
        }

        val key = ContentKey.batchDownload(query.tenantKey, query.batchKey, downloadFormat.name, query.part)
        val stored = contentStore.get(key)
            ?: throw BatchDownloadException.notFound("Assembled content not found")

        val contentType = when (downloadFormat) {
            BatchDownloadFormat.ZIP -> "application/zip"
            BatchDownloadFormat.MERGED_PDF -> "application/pdf"
        }
        val extension = when (downloadFormat) {
            BatchDownloadFormat.ZIP -> "zip"
            BatchDownloadFormat.MERGED_PDF -> "pdf"
        }
        val formatInfix = when (downloadFormat) {
            BatchDownloadFormat.ZIP -> ""
            BatchDownloadFormat.MERGED_PDF -> "-merged"
        }
        val partSuffix = if (parts > 1) "-part-${query.part}" else ""
        val filename = "batch-${query.batchKey.value}$formatInfix$partSuffix.$extension"

        return DownloadResult(
            content = stored,
            filename = filename,
            contentType = contentType,
        )
    }

    private fun getBatch(tenantKey: TenantKey, batchKey: BatchKey): BatchRecord? = jdbi.withHandle<BatchRecord?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT total_count, final_completed_count, final_failed_count,
                       assembly_status, download_formats, download_parts, completed_at
                FROM document_generation_batches
                WHERE id = :batchId AND tenant_key = :tenantKey
                """,
        )
            .bind("batchId", batchKey)
            .bind("tenantKey", tenantKey)
            .map { rs, _ ->
                BatchRecord(
                    totalCount = rs.getInt("total_count"),
                    completedCount = rs.getInt("final_completed_count"),
                    failedCount = rs.getInt("final_failed_count"),
                    assemblyStatus = AssemblyStatus.valueOf(rs.getString("assembly_status")),
                    downloadFormats = parseFormats(rs.getString("download_formats")),
                    downloadPartsJson = rs.getString("download_parts"),
                    completedAt = rs.getTimestamp("completed_at"),
                )
            }
            .findOne()
            .orElse(null)
    }

    private fun getSingleCompletedDocument(batchKey: BatchKey): SingleDoc? = jdbi.withHandle<SingleDoc?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT document_key, filename
                FROM document_generation_requests
                WHERE batch_id = :batchId AND status = 'COMPLETED' AND document_key IS NOT NULL
                LIMIT 1
                """,
        )
            .bind("batchId", batchKey)
            .map { rs, _ ->
                SingleDoc(
                    documentKey = DocumentKey.of(rs.getObject("document_key", java.util.UUID::class.java)),
                    filename = rs.getString("filename"),
                )
            }
            .findOne()
            .orElse(null)
    }

    private fun parseFormats(json: String): List<BatchDownloadFormat> {
        if (json.isBlank() || json == "[]") return emptyList()
        return json.trim('[', ']').split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .map { BatchDownloadFormat.valueOf(it) }
    }

    private data class BatchRecord(
        val totalCount: Int,
        val completedCount: Int,
        val failedCount: Int,
        val assemblyStatus: AssemblyStatus,
        val downloadFormats: List<BatchDownloadFormat>,
        val downloadPartsJson: String,
        val completedAt: java.sql.Timestamp?,
    ) {
        fun partsForFormat(format: BatchDownloadFormat): Int {
            if (downloadPartsJson.isBlank() || downloadPartsJson == "{}") return 0
            val formatKey = format.name
            val keyIndex = downloadPartsJson.indexOf("\"$formatKey\"")
            if (keyIndex == -1) return 0
            val arrayStart = downloadPartsJson.indexOf('[', keyIndex)
            val arrayEnd = downloadPartsJson.indexOf(']', arrayStart)
            if (arrayStart == -1 || arrayEnd == -1) return 0
            val arrayContent = downloadPartsJson.substring(arrayStart + 1, arrayEnd)
            if (arrayContent.isBlank()) return 0
            return arrayContent.split("partNumber").size - 1
        }
    }

    private data class SingleDoc(
        val documentKey: DocumentKey,
        val filename: String?,
    )
}
