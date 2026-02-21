package app.epistola.suite.storage

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream

/**
 * AWS S3-backed content store.
 *
 * Uses a single bucket with prefix-based tenant isolation:
 * `assets/{tenantId}/{assetId}`, `documents/{tenantId}/{documentId}`.
 */
class S3ContentStore(
    private val s3Client: S3Client,
    private val bucket: String,
) : ContentStore {

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long) {
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(sizeBytes)
            .build()

        s3Client.putObject(request, RequestBody.fromInputStream(content, sizeBytes))
    }

    override fun get(key: String): StoredContent? = try {
        val request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()

        val response = s3Client.getObject(request)
        StoredContent(
            content = response,
            contentType = response.response().contentType(),
            sizeBytes = response.response().contentLength(),
        )
    } catch (_: NoSuchKeyException) {
        null
    }

    override fun delete(key: String): Boolean {
        // S3 DeleteObject is idempotent — doesn't error on missing keys.
        // Check existence first to return accurate boolean.
        if (!exists(key)) return false

        val request = DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()

        s3Client.deleteObject(request)
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
