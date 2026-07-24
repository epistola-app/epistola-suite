// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import app.epistola.suite.time.EpistolaClock
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

    override fun putIfAbsent(scope: String, sha256: String, content: ByteArray, contentType: String, sizeBytes: Long) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes, created_at)
                VALUES (:scope, :hash, :content, :contentType, :sizeBytes, :createdAt)
                ON CONFLICT (scope, content_hash) DO NOTHING
                """,
            )
                .bind("scope", scope)
                .bind("hash", sha256)
                .bind("content", content)
                .bind("contentType", contentType)
                .bind("sizeBytes", sizeBytes)
                .bind("createdAt", EpistolaClock.offsetDateTime())
                .execute()
        }
    }

    override fun get(scope: String, sha256: String): StoredContent? = jdbi.withHandle<StoredContent?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT content, content_type, size_bytes
            FROM asset_content
            WHERE scope = :scope AND content_hash = :hash
            """,
        )
            .bind("scope", scope)
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

    override fun exists(scope: String, sha256: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT 1 FROM asset_content WHERE scope = :scope AND content_hash = :hash")
            .bind("scope", scope)
            .bind("hash", sha256)
            .mapTo(Int::class.java)
            .findOne()
            .isPresent
    }
}
