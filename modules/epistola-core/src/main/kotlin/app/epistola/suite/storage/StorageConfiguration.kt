// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import app.epistola.suite.documents.cleanup.PartitionProperties
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
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

    /**
     * The pluggable **document** content store (ephemeral generated PDFs, issue #738).
     * Each backend reclaims its own way — PostgreSQL via `document_content` partition
     * drops, S3 via a bucket lifecycle rule, filesystem via an age sweep.
     */
    @Bean
    fun documentContentStore(properties: StorageProperties, jdbi: Jdbi, meterRegistry: MeterRegistry): DocumentContentStore {
        warnIfAlphaBackend(properties.backend)
        val backendName = properties.backend.name.lowercase()
        val store: DocumentContentStore = when (properties.backend) {
            StorageBackend.POSTGRES -> {
                logger.info("Using PostgreSQL document content store (document_content)")
                PostgresDocumentContentStore(jdbi)
            }

            StorageBackend.S3 -> {
                require(properties.s3.bucket.isNotBlank()) { "epistola.storage.s3.bucket must be set when using S3 backend" }
                logger.info("Using S3 document content store (bucket={})", properties.s3.bucket)
                S3DocumentContentStore(buildS3Client(properties), properties.s3.bucket)
            }

            StorageBackend.FILESYSTEM -> {
                val basePath = Path.of(properties.filesystem.basePath)
                logger.info("Using filesystem document content store (basePath={})", basePath.toAbsolutePath())
                FilesystemDocumentContentStore(basePath)
            }

            StorageBackend.MEMORY -> {
                logger.info("Using in-memory document content store")
                InMemoryDocumentContentStore()
            }
        }
        return InstrumentedDocumentContentStore(store, meterRegistry, backendName)
    }

    /**
     * On the S3 backend, ensure a bucket lifecycle rule expires the `documents/` prefix
     * (#738), unless the operator opts out via `manage-document-lifecycle=false`. A
     * no-op singleton for every other backend, which reclaim differently (PostgreSQL
     * partition drops, filesystem sweep).
     */
    @Bean
    fun s3DocumentRetentionInitializer(
        properties: StorageProperties,
        partitions: PartitionProperties,
    ): SmartInitializingSingleton {
        if (properties.backend != StorageBackend.S3 || !properties.s3.manageDocumentLifecycle) {
            return SmartInitializingSingleton { }
        }
        // 31 days/month errs toward keeping blobs slightly longer than the partition
        // window — never expiring one still within retention.
        val retentionDays = properties.s3.documentRetentionDays ?: (partitions.retentionMonths * 31)
        return S3DocumentRetentionInitializer(buildS3Client(properties), properties.s3.bucket, retentionDays)
    }

    /**
     * PostgreSQL is the only tested/supported document-content backend. S3 and filesystem are
     * wired but have never been exercised by CI or in production, so 2.0 refuses to start on them
     * rather than run on something untested; their stores stay, to be enabled once tested. MEMORY
     * is test-only.
     */
    private fun warnIfAlphaBackend(backend: StorageBackend) {
        when (backend) {
            StorageBackend.S3, StorageBackend.FILESYSTEM ->
                throw IllegalStateException(
                    "epistola.storage.backend=${backend.name.lowercase()} is not supported: only postgres is tested. " +
                        "Set epistola.storage.backend=postgres.",
                )

            StorageBackend.MEMORY ->
                logger.warn("Storage backend MEMORY is for tests only and does not persist content across restarts.")

            StorageBackend.POSTGRES -> Unit
        }
    }

    private fun buildS3Client(properties: StorageProperties): S3Client {
        val clientBuilder = S3Client.builder()
            .region(Region.of(properties.s3.region))
        properties.s3.endpoint?.let { clientBuilder.endpointOverride(URI.create(it)) }
        return clientBuilder.build()
    }
}
