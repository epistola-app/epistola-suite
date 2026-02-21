package app.epistola.suite.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Abstract contract test for [ContentStore] implementations.
 *
 * Each backend implements this to verify it satisfies the ContentStore contract.
 */
abstract class ContentStoreContractTest {

    abstract fun createStore(): ContentStore

    private val store by lazy { createStore() }

    private val key = "test/tenant-1/abc-123"
    private val content = "hello world".toByteArray()
    private val contentType = "application/octet-stream"

    @Test
    fun `put and get round-trips content`() {
        store.put(key, ByteArrayInputStream(content), contentType, content.size.toLong())

        val stored = store.get(key)

        assertThat(stored).isNotNull
        assertThat(stored!!.content.readAllBytes()).isEqualTo(content)
        assertThat(stored.contentType).isEqualTo(contentType)
        assertThat(stored.sizeBytes).isEqualTo(content.size.toLong())
    }

    @Test
    fun `get returns null for missing key`() {
        assertThat(store.get("nonexistent/key")).isNull()
    }

    @Test
    fun `exists returns true for stored content`() {
        store.put(key, ByteArrayInputStream(content), contentType, content.size.toLong())

        assertThat(store.exists(key)).isTrue()
    }

    @Test
    fun `exists returns false for missing key`() {
        assertThat(store.exists("nonexistent/key")).isFalse()
    }

    @Test
    fun `delete removes content`() {
        store.put(key, ByteArrayInputStream(content), contentType, content.size.toLong())

        val deleted = store.delete(key)

        assertThat(deleted).isTrue()
        assertThat(store.get(key)).isNull()
        assertThat(store.exists(key)).isFalse()
    }

    @Test
    fun `delete returns false for missing key`() {
        assertThat(store.delete("nonexistent/key")).isFalse()
    }

    @Test
    fun `put overwrites existing content`() {
        store.put(key, ByteArrayInputStream(content), contentType, content.size.toLong())

        val newContent = "updated content".toByteArray()
        store.put(key, ByteArrayInputStream(newContent), "text/plain", newContent.size.toLong())

        val stored = store.get(key)
        assertThat(stored).isNotNull
        assertThat(stored!!.content.readAllBytes()).isEqualTo(newContent)
        assertThat(stored.contentType).isEqualTo("text/plain")
    }

    @Test
    fun `handles nested key paths`() {
        val nestedKey = "assets/tenant-abc/sub/folder/image.png"
        store.put(nestedKey, ByteArrayInputStream(content), contentType, content.size.toLong())

        assertThat(store.exists(nestedKey)).isTrue()
        assertThat(store.get(nestedKey)!!.content.readAllBytes()).isEqualTo(content)
    }
}
