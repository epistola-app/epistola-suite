package app.epistola.suite.storage

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "epistola.storage")
data class StorageProperties(
    val backend: StorageBackend = StorageBackend.POSTGRES,
    val s3: S3Properties = S3Properties(),
    val filesystem: FilesystemProperties = FilesystemProperties(),
)

/**
 * Where **document** content (generated PDFs) is stored. Asset content is always in
 * PostgreSQL regardless of this setting (issue #738).
 *
 * Only [POSTGRES] is tested and supported for production. [S3] and [FILESYSTEM] are
 * **alpha** — wired end-to-end but not exercised by CI or run in production, so their
 * retention paths (S3 bucket lifecycle rule, filesystem age sweep) are unvalidated.
 * [MEMORY] is for tests only.
 */
enum class StorageBackend {
    /** Tested, supported default: the partitioned `document_content` table. */
    POSTGRES,

    /** Alpha, untested: single S3 bucket + a `documents/` lifecycle rule for retention. */
    S3,

    /** Alpha, untested: files under a base path + an age sweep for retention. */
    FILESYSTEM,

    /** Test-only: in-memory, does not persist across restarts. */
    MEMORY,
}

data class S3Properties(
    val bucket: String = "",
    val region: String = "eu-west-1",
    val endpoint: String? = null,
    /**
     * Whether the app manages the `documents/` expiration lifecycle rule on the bucket
     * (#738). When true (default), it reconciles a single rule by id, preserving any
     * other rules already on the bucket. Set false to manage the bucket lifecycle
     * entirely yourself (the app then never touches it).
     */
    val manageDocumentLifecycle: Boolean = true,
    /**
     * Days after which the `documents/` prefix lifecycle rule expires document blobs
     * (#738). When null, derived from `epistola.partitions.retention-months`.
     */
    val documentRetentionDays: Int? = null,
)

data class FilesystemProperties(
    val basePath: String = "./content-store",
)
