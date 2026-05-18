package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.queries.GetCatalogReleaseStatus
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports all resources in a catalog as a self-contained ZIP archive.
 * Content is assembled by [CatalogContentBuilder] (shared with fingerprinting),
 * so the exported bytes are exactly the fingerprinted bytes.
 */
data class ExportCatalogZip(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<ExportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

data class ExportCatalogZipResult(
    val zipBytes: ByteArray,
    val filename: String,
)

@Component
class ExportCatalogZipHandler(
    private val objectMapper: ObjectMapper,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val sizeLimits: CatalogSizeLimits,
) : CommandHandler<ExportCatalogZip, ExportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ExportCatalogZip): ExportCatalogZipResult {
        val content = contentBuilder.build(command.tenantKey, command.catalogKey)

        // The emitted fingerprint always describes the actual exported bytes.
        // The version label encodes release state: a clean released version
        // when the working copy matches the latest release, a `-dev`-suffixed
        // label when it drifted (unreleased edits) or was never released.
        // Export is never hard-blocked — `-dev` makes drift unmistakable.
        val fingerprint = fingerprintService.fingerprint(content)
        val status = GetCatalogReleaseStatus(command.tenantKey, command.catalogKey).query()
        val version = when {
            status.latestVersion == null -> {
                logger.warn("Exporting never-released catalog '{}' as 0.0.0-dev", command.catalogKey.value)
                "0.0.0-dev"
            }
            status.latestFingerprint != fingerprint -> {
                logger.warn(
                    "Exporting catalog '{}' with unreleased changes — labelling {}-dev",
                    command.catalogKey.value,
                    status.latestVersion,
                )
                "${status.latestVersion}-dev"
            }
            else -> status.latestVersion
        }
        val releasedAt = if (version.endsWith("-dev")) null else OffsetDateTime.now().toString()
        val manifest = content.toManifest(
            ReleaseInfo(version = version, releasedAt = releasedAt, fingerprint = fingerprint),
        )

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("catalog.json"))
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest))
            zip.closeEntry()

            for ((key, detail) in content.resourceDetails) {
                zip.putNextEntry(ZipEntry("resources/$key.json"))
                zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(detail))
                zip.closeEntry()
            }

            for ((filename, bytes) in content.assetContents) {
                zip.putNextEntry(ZipEntry("resources/asset/$filename"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        val zipBytes = baos.toByteArray()
        require(zipBytes.size <= sizeLimits.maxDecompressedSize.toBytes()) {
            "Catalog export exceeds maximum size of ${sizeLimits.maxDecompressedSize} " +
                "(actual: ${zipBytes.size / 1024 / 1024} MB)"
        }

        val filename = "${command.catalogKey.value}-$version.zip"
        return ExportCatalogZipResult(zipBytes = zipBytes, filename = filename)
    }
}
