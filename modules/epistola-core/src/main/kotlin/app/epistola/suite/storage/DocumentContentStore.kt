// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import java.io.InputStream
import java.time.OffsetDateTime

/**
 * Pluggable storage for **ephemeral document** blobs (generated PDFs) — issue #738.
 *
 * Split out of the old shared [ContentStore] so document content has a lifecycle that
 * matches the `documents` metadata it belongs to and is reclaimed automatically when
 * that metadata ages out. Each backend reclaims its own way:
 *
 * - **PostgreSQL** — a RANGE-partitioned `document_content` table dropped in lockstep
 *   with the `documents` partitions by `PartitionMaintenanceScheduler`.
 * - **S3** — a bucket lifecycle rule expiring the `documents/` prefix.
 * - **Filesystem** — an age-based sweep (see [ContentRetentionMaintainer]).
 *
 * Keys keep the existing `documents/{tenantId}/{documentId}` convention
 * ([ContentKey.document]) so existing S3 / filesystem blobs need no migration.
 *
 * Permanent asset blobs live in [AssetContentStore] (always PostgreSQL), never here.
 */
interface DocumentContentStore {

    /**
     * Store document content at [key]. Overwrites any existing content for the same
     * (key, [createdAt]).
     *
     * [createdAt] is the **owning document's** `created_at`: the PostgreSQL backend
     * uses it as the partition key so the blob lands in the same monthly partition as
     * its metadata and ages out with it; the filesystem backend uses it as the
     * sweep-age basis; the S3 backend ignores it (its lifecycle rule acts on write time).
     */
    fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long, createdAt: OffsetDateTime)

    /** Retrieve content by key, or null if not found. */
    fun get(key: String): StoredContent?

    /**
     * Delete content at [key].
     * @return true if content was deleted, false if the key did not exist.
     */
    fun delete(key: String): Boolean

    /** Check whether content exists at [key]. */
    fun exists(key: String): Boolean
}
