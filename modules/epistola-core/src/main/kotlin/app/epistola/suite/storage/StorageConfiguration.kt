package app.epistola.suite.storage

import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Path

@Configuration
@EnableConfigurationProperties(StorageProperties::class)
class StorageConfiguration {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun contentStore(properties: StorageProperties, jdbi: Jdbi): ContentStore {
        val store = when (properties.backend) {
            StorageBackend.POSTGRES -> {
                logger.info("Using PostgreSQL content store")
                PostgresContentStore(jdbi)
            }

            StorageBackend.S3 -> {
                require(properties.s3.bucket.isNotBlank()) { "epistola.storage.s3.bucket must be set when using S3 backend" }
                logger.info("Using S3 content store (bucket={})", properties.s3.bucket)
                val clientBuilder = S3Client.builder()
                    .region(Region.of(properties.s3.region))
                properties.s3.endpoint?.let { clientBuilder.endpointOverride(URI.create(it)) }
                S3ContentStore(clientBuilder.build(), properties.s3.bucket)
            }

            StorageBackend.FILESYSTEM -> {
                val basePath = Path.of(properties.filesystem.basePath)
                logger.info("Using filesystem content store (basePath={})", basePath.toAbsolutePath())
                FilesystemContentStore(basePath)
            }

            StorageBackend.MEMORY -> {
                logger.info("Using in-memory content store")
                InMemoryContentStore()
            }
        }
        return store
    }
}
