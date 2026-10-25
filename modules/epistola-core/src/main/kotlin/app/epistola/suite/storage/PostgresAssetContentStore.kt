// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

/**
 * The (always PostgreSQL) [AssetContentStore], backed by the content-addressable
 * `asset_content` table (issue #738). A `@Component` because, unlike the document
 * store, the asset store is not pluggable — it never lives in S3 or on the filesystem.
 */
@Component
class PostgresAssetContentStore(
    private val jdbi: Jdbi,
) : AssetContentStore {

    override fun putIfAbsent(sha256: String, content: ByteArray, contentType: String, sizeBytes: Long) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO asset_content (content_hash, content, content_type, size_bytes, created_at)
                VALUES (:hash, :content, :contentType, :sizeBytes, NOW())
                -- Writing bytes that are there already touches them: the sweeps' grace period counts
                -- from the last write, and the row is locked until this commits, so a blob an upload
                -- is about to point at again is never collected from under it (ADR 0033). Database
                -- time, as the collector compares it with NOW(): one clock on both sides of the grace.
                ON CONFLICT (content_hash) DO UPDATE SET created_at = GREATEST(asset_content.created_at, EXCLUDED.created_at)
                """,
            )
                .bind("hash", sha256)
                .bind("content", content)
                .bind("contentType", contentType)
                .bind("sizeBytes", sizeBytes)
                .execute()
        }
    }

    override fun get(sha256: String): StoredContent? = jdbi.withHandle<StoredContent?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT content, content_type, size_bytes
            FROM asset_content
            WHERE content_hash = :hash
            """,
        )
            .bind("hash", sha256)
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

    override fun exists(sha256: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT 1 FROM asset_content WHERE content_hash = :hash")
            .bind("hash", sha256)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
}
