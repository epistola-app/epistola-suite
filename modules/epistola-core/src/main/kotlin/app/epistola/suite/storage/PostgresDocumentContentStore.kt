// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import org.jdbi.v3.core.Jdbi
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.OffsetDateTime

/**
 * PostgreSQL-backed [DocumentContentStore] using the RANGE-partitioned
 * `document_content` table (issue #738).
 *
 * The blob is inserted with the owning document's `created_at`, so it lands in the
 * same monthly partition as the `documents` row and is reclaimed together when
 * `PartitionMaintenanceScheduler` drops that partition — an O(1) `DROP TABLE`, no
 * per-row DELETE and no BYTEA bloat.
 */
class PostgresDocumentContentStore(
    private val jdbi: Jdbi,
) : DocumentContentStore {

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long, createdAt: OffsetDateTime) {
        val bytes = content.readAllBytes()
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO document_content (key, content, content_type, size_bytes, created_at)
                VALUES (:key, :content, :contentType, :sizeBytes, :createdAt)
                ON CONFLICT (key, created_at) DO UPDATE
                SET content = EXCLUDED.content,
                    content_type = EXCLUDED.content_type,
                    size_bytes = EXCLUDED.size_bytes
                """,
            )
                .bind("key", key)
                .bind("content", bytes)
                .bind("contentType", contentType)
                .bind("sizeBytes", sizeBytes)
                .bind("createdAt", createdAt)
                .execute()
        }
    }

    override fun get(key: String): StoredContent? = jdbi.withHandle<StoredContent?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT content, content_type, size_bytes
            FROM document_content
            WHERE key = :key
            """,
        )
            .bind("key", key)
            .map { rs, _ ->
                StoredContent(
                    content = ByteArrayInputStream(rs.getBytes("content")),
                    contentType = rs.getString("content_type"),
                    sizeBytes = rs.getLong("size_bytes"),
                )
            }
            .findOne()
            .orElse(null)
    }

    override fun delete(key: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate("DELETE FROM document_content WHERE key = :key")
            .bind("key", key)
            .execute() > 0
    }

    override fun exists(key: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT 1 FROM document_content WHERE key = :key")
            .bind("key", key)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
}
