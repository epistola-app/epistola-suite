// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.time.OffsetDateTime

/**
 * AWS S3-backed [DocumentContentStore]. Documents keep their existing
 * `documents/{tenantId}/{documentId}` keys, so no object needs to move.
 *
 * Retention is enforced natively by a bucket **lifecycle rule** on the `documents/`
 * prefix (reconciled at startup by `S3DocumentRetentionInitializer`), so [createdAt]
 * is not needed here — S3 expires objects by their own write time.
 */
class S3DocumentContentStore(
    private val s3Client: S3Client,
    private val bucket: String,
) : DocumentContentStore {

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long, createdAt: OffsetDateTime) {
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(sizeBytes)
            .build()

        s3Client.putObject(request, RequestBody.fromInputStream(content, sizeBytes))
    }

    override fun get(key: String): StoredContent? = try {
        val response = s3Client.getObject(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
        StoredContent(
            content = response,
            contentType = response.response().contentType(),
            sizeBytes = response.response().contentLength(),
        )
    } catch (_: NoSuchKeyException) {
        null
    }

    override fun delete(key: String): Boolean {
        // S3 DeleteObject is idempotent — check existence first to return an accurate boolean.
        if (!exists(key)) return false
        s3Client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
        return true
    }

    override fun exists(key: String): Boolean = try {
        s3Client.headObject(
            HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
        true
    } catch (_: NoSuchKeyException) {
        false
    }
}
