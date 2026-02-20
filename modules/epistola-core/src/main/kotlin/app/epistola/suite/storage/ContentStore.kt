package app.epistola.suite.storage

import java.io.InputStream

/**
 * Pluggable content storage abstraction.
 *
 * Stores binary content (images, PDFs) keyed by a string path.
 * Keys follow S3-style conventions: `assets/{tenantId}/{assetId}`, `documents/{tenantId}/{documentId}`.
 *
 * Implementations: PostgreSQL (default), S3, filesystem, in-memory (tests).
 */
interface ContentStore {

    /**
     * Store content at the given key. Overwrites any existing content.
     */
    fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long)

    /**
     * Retrieve content by key, or null if not found.
     */
    fun get(key: String): StoredContent?

    /**
     * Delete content at the given key.
     * @return true if content was deleted, false if the key did not exist.
     */
    fun delete(key: String): Boolean

    /**
     * Check whether content exists at the given key.
     */
    fun exists(key: String): Boolean
}

/**
 * Binary content read back from the store.
 */
data class StoredContent(
    val content: InputStream,
    val contentType: String,
    val sizeBytes: Long,
)
