package app.epistola.suite.storage

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory content store backed by [ConcurrentHashMap].
 * Used in tests for fast, isolated storage without external dependencies.
 */
class InMemoryContentStore : ContentStore {

    private data class Entry(
        val content: ByteArray,
        val contentType: String,
    )

    private val store = ConcurrentHashMap<String, Entry>()

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long) {
        store[key] = Entry(content.readAllBytes(), contentType)
    }

    override fun get(key: String): StoredContent? {
        val entry = store[key] ?: return null
        return StoredContent(
            content = ByteArrayInputStream(entry.content),
            contentType = entry.contentType,
            sizeBytes = entry.content.size.toLong(),
        )
    }

    override fun delete(key: String): Boolean = store.remove(key) != null

    override fun exists(key: String): Boolean = store.containsKey(key)

    /** Visible for tests: clear all stored content. */
    fun clear() = store.clear()
}
