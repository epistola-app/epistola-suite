package app.epistola.suite.storage

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [InstrumentedContentStore] metrics instrumentation.
 *
 * Uses [SimpleMeterRegistry] and [InMemoryContentStore] for fast,
 * Spring-context-free testing of the decorator's metric recording.
 */
class InstrumentedContentStoreTest {

    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var delegate: InMemoryContentStore
    private lateinit var store: InstrumentedContentStore

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        delegate = InMemoryContentStore()
        store = InstrumentedContentStore(delegate, meterRegistry, "test")
    }

    @Test
    fun `put records timer with success outcome and size summary`() {
        val content = "hello world".toByteArray()
        store.put("test-key", ByteArrayInputStream(content), "text/plain", content.size.toLong())

        val timer = meterRegistry.find("epistola.storage.operation.duration")
            .tag("operation", "put")
            .tag("outcome", "success")
            .tag("backend", "test")
            .timer()
        assertNotNull(timer, "Timer should be registered for put operation")
        assertEquals(1, timer.count())
        assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0)

        val summary = meterRegistry.find("epistola.storage.put.bytes")
            .tag("backend", "test")
            .summary()
        assertNotNull(summary, "Size summary should be registered")
        assertEquals(1, summary.count())
        assertEquals(content.size.toDouble(), summary.totalAmount())
    }

    @Test
    fun `get records timer with success outcome`() {
        val content = "test content".toByteArray()
        store.put("key", ByteArrayInputStream(content), "text/plain", content.size.toLong())

        val result = store.get("key")
        assertNotNull(result)

        val timer = meterRegistry.find("epistola.storage.operation.duration")
            .tag("operation", "get")
            .tag("outcome", "success")
            .timer()
        assertNotNull(timer, "Timer should be registered for get operation")
        assertEquals(1, timer.count())
    }

    @Test
    fun `get returns null for missing key and still records timer`() {
        val result = store.get("missing-key")
        assertNull(result)

        val timer = meterRegistry.find("epistola.storage.operation.duration")
            .tag("operation", "get")
            .tag("outcome", "success")
            .timer()
        assertNotNull(timer, "Timer should be registered even for null result")
        assertEquals(1, timer.count())
    }

    @Test
    fun `delete records timer with success outcome`() {
        val content = "to delete".toByteArray()
        store.put("del-key", ByteArrayInputStream(content), "text/plain", content.size.toLong())

        val deleted = store.delete("del-key")
        assertTrue(deleted)

        val timer = meterRegistry.find("epistola.storage.operation.duration")
            .tag("operation", "delete")
            .tag("outcome", "success")
            .timer()
        assertNotNull(timer, "Timer should be registered for delete operation")
        assertEquals(1, timer.count())
    }

    @Test
    fun `exists records timer with success outcome`() {
        store.exists("any-key")

        val timer = meterRegistry.find("epistola.storage.operation.duration")
            .tag("operation", "exists")
            .tag("outcome", "success")
            .timer()
        assertNotNull(timer, "Timer should be registered for exists operation")
        assertEquals(1, timer.count())
    }

    @Test
    fun `failed operation records timer with failure outcome`() {
        val failing = InstrumentedContentStore(FailingContentStore(), meterRegistry, "failing")

        assertFailsWith<RuntimeException> {
            failing.get("any-key")
        }

        val timer = meterRegistry.find("epistola.storage.operation.duration")
            .tag("operation", "get")
            .tag("outcome", "failure")
            .tag("backend", "failing")
            .timer()
        assertNotNull(timer, "Timer should be registered with failure outcome")
        assertEquals(1, timer.count())
    }

    @Test
    fun `exceptions propagate through the decorator`() {
        val failing = InstrumentedContentStore(FailingContentStore(), meterRegistry, "failing")

        val exception = assertFailsWith<RuntimeException> {
            failing.put("key", ByteArrayInputStream(ByteArray(0)), "text/plain", 0)
        }
        assertEquals("put failed", exception.message)
    }

    /** Content store that throws on every operation. */
    private class FailingContentStore : ContentStore {
        override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long) = throw RuntimeException("put failed")

        override fun get(key: String): StoredContent? = throw RuntimeException("get failed")
        override fun delete(key: String): Boolean = throw RuntimeException("delete failed")
        override fun exists(key: String): Boolean = throw RuntimeException("exists failed")
    }
}
