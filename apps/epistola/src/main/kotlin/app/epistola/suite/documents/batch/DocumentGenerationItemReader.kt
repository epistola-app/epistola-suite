package app.epistola.suite.documents.batch

import app.epistola.suite.documents.model.DocumentGenerationItem
import app.epistola.suite.documents.model.ItemStatus
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import java.util.UUID

/**
 * Spring Batch ItemReader for document generation items.
 *
 * Reads pending items for a given request and marks them as IN_PROGRESS.
 * This ensures items are processed exactly once even if the job is restarted.
 *
 * @param jdbi JDBI instance for database access
 * @param requestId The generation request ID to process
 */
class DocumentGenerationItemReader(
    private val jdbi: Jdbi,
    private val requestId: UUID,
) : ItemReader<DocumentGenerationItem> {

    private val logger = LoggerFactory.getLogger(javaClass)
    private var itemsIterator: Iterator<DocumentGenerationItem>? = null
    private var initialized = false

    override fun read(): DocumentGenerationItem? {
        if (!initialized) {
            initialize()
        }

        return if (itemsIterator?.hasNext() == true) {
            itemsIterator!!.next()
        } else {
            null // Signals end of data
        }
    }

    private fun initialize() {
        logger.info("Initializing reader for request: {}", requestId)

        val items = jdbi.inTransaction<List<DocumentGenerationItem>, Exception> { handle ->
            // Fetch pending items and mark as IN_PROGRESS atomically
            val pendingItems = handle.createQuery(
                """
                UPDATE document_generation_items
                SET status = 'IN_PROGRESS', started_at = NOW()
                WHERE request_id = :requestId
                  AND status = 'PENDING'
                RETURNING id, request_id, template_id, variant_id, version_id, environment_id,
                          data, filename, status, error_message, document_id,
                          created_at, started_at, completed_at
                """
            )
                .bind("requestId", requestId)
                .mapTo<DocumentGenerationItem>()
                .list()

            logger.info("Found {} pending items for request {}", pendingItems.size, requestId)
            pendingItems
        }

        itemsIterator = items.iterator()
        initialized = true
    }
}
