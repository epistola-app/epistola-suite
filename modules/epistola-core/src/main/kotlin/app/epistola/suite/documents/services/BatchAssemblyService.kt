package app.epistola.suite.documents.services

import app.epistola.generation.pdf.PdfMergerUtil
import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.BatchDownloadProperties
import app.epistola.suite.documents.model.AssemblyStatus
import app.epistola.suite.documents.model.BatchDownloadFormat
import app.epistola.suite.documents.model.DownloadPartInfo
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles batch download files (ZIP and/or merged PDF) after batch completion.
 *
 * Strategy: downloads all source PDFs from ContentStore to a local temp directory,
 * then assembles from local disk. Assembled files are stored back in ContentStore.
 */
@Component
class BatchAssemblyService(
    private val jdbi: Jdbi,
    private val contentStore: ContentStore,
    private val properties: BatchDownloadProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxPartSizeBytes = properties.maxPartSizeMb * 1024 * 1024

    /**
     * Assemble download files for a completed batch. Called async after batch completion.
     */
    @Async
    fun assembleDownloads(tenantKey: TenantKey, batchKey: BatchKey) {
        try {
            updateAssemblyStatus(batchKey, AssemblyStatus.IN_PROGRESS)
            doAssemble(tenantKey, batchKey)
            updateAssemblyStatus(batchKey, AssemblyStatus.COMPLETED)
            logger.info("Batch {} assembly completed", batchKey.value)
        } catch (e: Exception) {
            logger.error("Batch {} assembly failed: {}", batchKey.value, e.message, e)
            updateAssemblyStatus(batchKey, AssemblyStatus.FAILED)
        }
    }

    private fun doAssemble(tenantKey: TenantKey, batchKey: BatchKey) {
        val formats = getDownloadFormats(batchKey)
        if (formats.isEmpty()) return

        val requests = getCompletedRequests(batchKey)
        if (requests.isEmpty()) {
            logger.warn("Batch {} has no completed requests, skipping assembly", batchKey.value)
            return
        }

        val tempDir = Files.createTempDirectory("batch-${batchKey.value}")
        try {
            stageSourcePdfs(tenantKey, requests, tempDir)

            val stagedFiles = requests.mapNotNull { req ->
                val file = tempDir.resolve("%04d.pdf".format(req.sequence)).toFile()
                if (file.exists()) {
                    StagedDocument(file, req.filename ?: "document-${req.documentKey?.value}.pdf")
                } else {
                    null
                }
            }

            val allParts = mutableMapOf<String, List<DownloadPartInfo>>()

            for (format in formats) {
                val parts = when (format) {
                    BatchDownloadFormat.ZIP -> assembleZip(tenantKey, batchKey, stagedFiles)
                    BatchDownloadFormat.MERGED_PDF -> assembleMergedPdf(tenantKey, batchKey, stagedFiles)
                }
                allParts[format.name] = parts
            }

            storeDownloadParts(batchKey, allParts)
        } finally {
            deleteRecursively(tempDir)
        }
    }

    private fun stageSourcePdfs(tenantKey: TenantKey, requests: List<RequestInfo>, tempDir: Path) {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val futures = mutableListOf<Future<*>>()
            val semaphore = Semaphore(properties.stagingParallelism)

            for (request in requests) {
                if (request.documentKey == null) continue
                semaphore.acquire()
                futures.add(
                    executor.submit {
                        try {
                            val key = ContentKey.document(tenantKey, request.documentKey)
                            val stored = contentStore.get(key)
                            if (stored != null) {
                                val target = tempDir.resolve("%04d.pdf".format(request.sequence))
                                stored.content.use { input ->
                                    Files.copy(input, target)
                                }
                            } else {
                                logger.warn("Content not found for document {} in batch", request.documentKey.value)
                            }
                        } finally {
                            semaphore.release()
                        }
                    },
                )
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }
    }

    private fun assembleZip(
        tenantKey: TenantKey,
        batchKey: BatchKey,
        stagedFiles: List<StagedDocument>,
    ): List<DownloadPartInfo> {
        val parts = mutableListOf<DownloadPartInfo>()
        var partNumber = 1
        var currentSize = 0L
        var buffer = ByteArrayOutputStream()
        var zip = ZipOutputStream(buffer)

        fun finalizePart() {
            zip.close()
            val bytes = buffer.toByteArray()
            storeAssembledPart(tenantKey, batchKey, "zip", partNumber, bytes, "application/zip")
            parts.add(DownloadPartInfo(partNumber, bytes.size.toLong()))
            partNumber++
            currentSize = 0L
            buffer = ByteArrayOutputStream()
            zip = ZipOutputStream(buffer)
        }

        for (staged in stagedFiles) {
            val fileSize = staged.file.length()

            if (currentSize > 0 && currentSize + fileSize > maxPartSizeBytes) {
                finalizePart()
            }

            zip.putNextEntry(ZipEntry(staged.filename))
            staged.file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            currentSize += fileSize
        }

        zip.close()
        val bytes = buffer.toByteArray()
        if (bytes.isNotEmpty()) {
            storeAssembledPart(tenantKey, batchKey, "zip", partNumber, bytes, "application/zip")
            parts.add(DownloadPartInfo(partNumber, bytes.size.toLong()))
        }

        return parts
    }

    private fun assembleMergedPdf(
        tenantKey: TenantKey,
        batchKey: BatchKey,
        stagedFiles: List<StagedDocument>,
    ): List<DownloadPartInfo> {
        val parts = mutableListOf<DownloadPartInfo>()
        var partNumber = 1
        var currentBatch = mutableListOf<File>()
        var currentSize = 0L

        fun finalizePart() {
            if (currentBatch.isEmpty()) return
            val output = ByteArrayOutputStream()
            PdfMergerUtil.mergePdfs(currentBatch, output)
            val bytes = output.toByteArray()
            storeAssembledPart(tenantKey, batchKey, "merged_pdf", partNumber, bytes, "application/pdf")
            parts.add(DownloadPartInfo(partNumber, bytes.size.toLong()))
            partNumber++
            currentBatch = mutableListOf()
            currentSize = 0L
        }

        for (staged in stagedFiles) {
            val fileSize = staged.file.length()

            if (currentSize > 0 && currentSize + fileSize > maxPartSizeBytes) {
                finalizePart()
            }

            currentBatch.add(staged.file)
            currentSize += fileSize
        }

        if (currentBatch.isNotEmpty()) {
            finalizePart()
        }

        return parts
    }

    private fun storeAssembledPart(
        tenantKey: TenantKey,
        batchKey: BatchKey,
        format: String,
        part: Int,
        bytes: ByteArray,
        contentType: String,
    ) {
        val key = ContentKey.batchDownload(tenantKey, batchKey, format, part)
        contentStore.put(key, ByteArrayInputStream(bytes), contentType, bytes.size.toLong())
        logger.debug("Stored batch {} {} part {} ({} bytes)", batchKey.value, format, part, bytes.size)
    }

    private fun storeDownloadParts(batchKey: BatchKey, parts: Map<String, List<DownloadPartInfo>>) {
        val json = buildString {
            append("{")
            parts.entries.forEachIndexed { index, (format, partList) ->
                if (index > 0) append(",")
                append("\"$format\":[")
                partList.forEachIndexed { pIndex, part ->
                    if (pIndex > 0) append(",")
                    append("{\"partNumber\":${part.partNumber},\"sizeBytes\":${part.sizeBytes}}")
                }
                append("]")
            }
            append("}")
        }
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_batches
                SET download_parts = :parts::jsonb
                WHERE id = :batchId
                """,
            )
                .bind("batchId", batchKey)
                .bind("parts", json)
                .execute()
        }
    }

    private fun updateAssemblyStatus(batchKey: BatchKey, status: AssemblyStatus) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_batches
                SET assembly_status = :status
                WHERE id = :batchId
                """,
            )
                .bind("batchId", batchKey)
                .bind("status", status.name)
                .execute()
        }
    }

    private fun getDownloadFormats(batchKey: BatchKey): List<BatchDownloadFormat> = jdbi.withHandle<List<BatchDownloadFormat>, Exception> { handle ->
        val json = handle.createQuery(
            "SELECT download_formats FROM document_generation_batches WHERE id = :batchId",
        )
            .bind("batchId", batchKey)
            .mapTo(String::class.java)
            .one()
        parseDownloadFormats(json)
    }

    private fun getCompletedRequests(batchKey: BatchKey): List<RequestInfo> = jdbi.withHandle<List<RequestInfo>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT sequence, document_key, filename
                FROM document_generation_requests
                WHERE batch_id = :batchId
                  AND status = 'COMPLETED'
                  AND document_key IS NOT NULL
                ORDER BY sequence
                """,
        )
            .bind("batchId", batchKey)
            .map { rs, _ ->
                RequestInfo(
                    sequence = rs.getInt("sequence"),
                    documentKey = rs.getObject("document_key", java.util.UUID::class.java)
                        ?.let { DocumentKey.of(it) },
                    filename = rs.getString("filename"),
                )
            }
            .list()
    }

    private fun parseDownloadFormats(json: String): List<BatchDownloadFormat> {
        if (json.isBlank() || json == "[]") return emptyList()
        return json.trim('[', ']').split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .map { BatchDownloadFormat.valueOf(it) }
    }

    private fun deleteRecursively(path: Path) {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private data class RequestInfo(
        val sequence: Int,
        val documentKey: DocumentKey?,
        val filename: String?,
    )

    private data class StagedDocument(
        val file: File,
        val filename: String,
    )
}
