package app.epistola.suite.storage

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.io.InputStream

/**
 * Decorator that wraps any [ContentStore] implementation and adds Micrometer metrics.
 *
 * Metrics:
 * - `epistola.storage.operation.duration` — Timer per operation (put/get/delete/exists) with outcome
 * - `epistola.storage.put.bytes` — DistributionSummary of content sizes being stored
 */
class InstrumentedContentStore(
    private val delegate: ContentStore,
    private val meterRegistry: MeterRegistry,
    private val backendName: String,
) : ContentStore {

    private val putSizeSummary = DistributionSummary.builder("epistola.storage.put.bytes")
        .tag("backend", backendName)
        .description("Content size being stored")
        .register(meterRegistry)

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long) {
        recordOperation("put") {
            delegate.put(key, content, contentType, sizeBytes)
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
