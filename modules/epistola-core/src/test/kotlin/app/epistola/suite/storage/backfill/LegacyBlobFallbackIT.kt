package app.epistola.suite.storage.backfill

import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The transitional fallback (#742) must serve blobs still living only in the legacy
 * `content_store` — the path that keeps documents/assets readable on every node while
 * the background `ContentBackfillRunner` migrates them (#738).
 */
class LegacyBlobFallbackIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var fallback: LegacyBlobFallback

    @Test
    fun `serves a document blob that only exists in the legacy content_store`() {
        val tenant = TenantKey.of("tenant-legacy-doc")
        val doc = DocumentKey.generate()
        val bytes = byteArrayOf(1, 2, 3, 4)
        seedLegacy(ContentKey.document(tenant, doc), bytes, "application/pdf")

        val stored = fallback.documentContent(tenant, doc)
        assertThat(stored).isNotNull
        assertThat(stored!!.content.readAllBytes()).isEqualTo(bytes)
        assertThat(stored.contentType).isEqualTo("application/pdf")

        assertThat(fallback.documentContent(tenant, DocumentKey.generate())).isNull()
    }

    @Test
    fun `serves an asset blob that only exists in the legacy content_store`() {
        val tenant = TenantKey.of("tenant-legacy-asset")
        val asset = AssetKey.generate()
        val bytes = byteArrayOf(9, 8, 7)
        seedLegacy(ContentKey.asset(tenant, asset), bytes, "image/png")

        assertThat(fallback.assetBytes(tenant, asset)).isEqualTo(bytes)
        assertThat(fallback.assetBytes(tenant, AssetKey.generate())).isNull()
    }

    private fun seedLegacy(key: String, bytes: ByteArray, contentType: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            INSERT INTO content_store (key, content, content_type, size_bytes, created_at)
            VALUES (:key, :bytes, :ct, :size, now())
            """,
        )
            .bind("key", key)
            .bind("bytes", bytes)
            .bind("ct", contentType)
            .bind("size", bytes.size.toLong())
            .execute()
    }
}
