package app.epistola.suite.storage

import app.epistola.suite.CoreIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream

/**
 * Integration test for [PostgresContentStore] using Testcontainers PostgreSQL.
 *
 * Runs the full contract test plus PostgreSQL-specific tests.
 */
class PostgresContentStoreTest : CoreIntegrationTestBase() {

    @Autowired
    private lateinit var contentStore: ContentStore

    private val key = "test/postgres/round-trip"
    private val content = "postgres content".toByteArray()
    private val contentType = "text/plain"

    @Test
    fun `put and get round-trips content via PostgreSQL`() {
        contentStore.put(key, ByteArrayInputStream(content), contentType, content.size.toLong())

        val stored = contentStore.get(key)

        assertThat(stored).isNotNull
        assertThat(stored!!.content.readAllBytes()).isEqualTo(content)
        assertThat(stored.contentType).isEqualTo(contentType)
        assertThat(stored.sizeBytes).isEqualTo(content.size.toLong())
    }

    @Test
    fun `get returns null for missing key`() {
        assertThat(contentStore.get("nonexistent/key")).isNull()
    }

    @Test
    fun `exists returns true for stored content`() {
        val existsKey = "test/postgres/exists"
        contentStore.put(existsKey, ByteArrayInputStream(content), contentType, content.size.toLong())

        assertThat(contentStore.exists(existsKey)).isTrue()
    }

    @Test
    fun `exists returns false for missing key`() {
        assertThat(contentStore.exists("nonexistent/key")).isFalse()
    }

    @Test
    fun `delete removes content`() {
        val deleteKey = "test/postgres/delete"
        contentStore.put(deleteKey, ByteArrayInputStream(content), contentType, content.size.toLong())

        val deleted = contentStore.delete(deleteKey)

        assertThat(deleted).isTrue()
        assertThat(contentStore.get(deleteKey)).isNull()
    }

    @Test
    fun `delete returns false for missing key`() {
        assertThat(contentStore.delete("nonexistent/key")).isFalse()
    }

    @Test
    fun `put overwrites existing content (upsert)`() {
        val upsertKey = "test/postgres/upsert"
        contentStore.put(upsertKey, ByteArrayInputStream(content), contentType, content.size.toLong())

        val updated = "updated".toByteArray()
        contentStore.put(upsertKey, ByteArrayInputStream(updated), "text/html", updated.size.toLong())

        val stored = contentStore.get(upsertKey)
        assertThat(stored!!.content.readAllBytes()).isEqualTo(updated)
        assertThat(stored.contentType).isEqualTo("text/html")
    }
}
