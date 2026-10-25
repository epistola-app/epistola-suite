// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

/**
 * Storage for **permanent** blobs: an image's bytes and a font face's file — issue #738.
 *
 * Content-addressable and deduplicated: blobs are keyed by their SHA-256, so identical bytes are
 * stored once per installation. Always PostgreSQL, regardless of the configured
 * `epistola.storage.backend` — they are small, low-churn, keep-forever reference data. A stored
 * content that needs one links to it through `resource_binaries` (ADR 0033).
 *
 * There is no public delete: a blob may back many contents, so reclamation is mark-and-sweep by the
 * content reaper once no `resource_binaries` row names its hash.
 */
interface AssetContentStore {

    /**
     * Store [content] under [sha256] if absent; if the blob already exists, only its write time is
     * touched (deduplication), so the content sweeps count their grace period from the last write.
     */
    fun putIfAbsent(sha256: String, content: ByteArray, contentType: String, sizeBytes: Long)

    /** Retrieve content by [sha256], or null if not found. */
    fun get(sha256: String): StoredContent?

    /** Check whether content exists under [sha256]. */
    fun exists(sha256: String): Boolean
}
