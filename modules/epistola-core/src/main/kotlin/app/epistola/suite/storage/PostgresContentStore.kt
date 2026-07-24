// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * PostgreSQL-backed content store using a dedicated `content_store` key-value table.
 *
 * This is the default backend — zero additional infrastructure required.
 */
class PostgresContentStore(
    private val jdbi: Jdbi,
) : ContentStore {

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long) {
        val bytes = content.readAllBytes()
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO content_store (key, content, content_type, size_bytes, created_at)
                VALUES (:key, :content, :contentType, :sizeBytes, :createdAt)
                ON CONFLICT (key) DO UPDATE
                SET content = EXCLUDED.content,
                    content_type = EXCLUDED.content_type,
                    size_bytes = EXCLUDED.size_bytes,
                    created_at = EXCLUDED.created_at
                """,
            )
                .bind("key", key)
                .bind("content", bytes)
                .bind("contentType", contentType)
                .bind("sizeBytes", sizeBytes)
                .bind("createdAt", EpistolaClock.offsetDateTime())
                .execute()
        }
    }

    override fun get(key: String): StoredContent? = jdbi.withHandle<StoredContent?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT content, content_type, size_bytes
            FROM content_store
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
        handle.createUpdate("DELETE FROM content_store WHERE key = :key")
            .bind("key", key)
            .execute() > 0
    }

    override fun exists(key: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT 1 FROM content_store WHERE key = :key")
            .bind("key", key)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
}
