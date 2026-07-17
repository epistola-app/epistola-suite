package app.epistola.suite.storage

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.io.InputStream
import java.time.OffsetDateTime

/**
 * Decorator wrapping any [DocumentContentStore] with Micrometer metrics, preserving
 * the existing `epistola.storage.*` meters (`backend` tag) from the pre-split store.
 */
class InstrumentedDocumentContentStore(
    private val delegate: DocumentContentStore,
    private val meterRegistry: MeterRegistry,
    private val backendName: String,
) : DocumentContentStore,
    ContentRetentionMaintainer {

    /**
     * Forward active reclamation to the delegate backend when it needs it (only the
     * filesystem backend does); a no-op for PostgreSQL (partition drops) and S3
     * (bucket lifecycle rule). Lets the reaper collect a single
     * [ContentRetentionMaintainer] without knowing the active backend.
     */
    override fun reclaim(retentionMonths: Int) {
        (delegate as? ContentRetentionMaintainer)?.reclaim(retentionMonths)
    }

    private val putSizeSummary = DistributionSummary.builder("epistola.storage.put.bytes")
        .tag("backend", backendName)
        .description("Content size being stored")
        .register(meterRegistry)

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long, createdAt: OffsetDateTime) {
        recordOperation("put") {
            delegate.put(key, content, contentType, sizeBytes, createdAt)
        }
        putSizeSummary.record(sizeBytes.toDouble())
    }

    override fun get(key: String): StoredContent? = recordOperation("get") {
        delegate.get(key)
    }

    override fun delete(key: String): Boolean = recordOperation("delete") {
        delegate.delete(key)
    }

    override fun exists(key: String): Boolean = recordOperation("exists") {
        delegate.exists(key)
    }

    private fun <T> recordOperation(operation: String, block: () -> T): T {
        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        return try {
            block()
        } catch (e: Exception) {
            outcome = "failure"
            throw e
        } finally {
            sample.stop(
                Timer.builder("epistola.storage.operation.duration")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .tag("backend", backendName)
                    .register(meterRegistry),
            )
        }
    }
}
