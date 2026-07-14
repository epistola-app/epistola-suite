package app.epistola.suite.storage

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "epistola.storage")
data class StorageProperties(
    val backend: StorageBackend = StorageBackend.POSTGRES,
    val s3: S3Properties = S3Properties(),
    val filesystem: FilesystemProperties = FilesystemProperties(),
)

enum class StorageBackend {
    POSTGRES,
    S3,
    FILESYSTEM,
    MEMORY,
}

data class S3Properties(
    val bucket: String = "",
    val region: String = "eu-west-1",
    val endpoint: String? = null,
    /**
     * Days after which the `documents/` prefix lifecycle rule expires document blobs
     * (#738). When null, derived from `epistola.partitions.retention-months`.
     */
    val documentRetentionDays: Int? = null,
)

data class FilesystemProperties(
    val basePath: String = "./content-store",
)
