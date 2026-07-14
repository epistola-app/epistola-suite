package app.epistola.suite.storage

import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.OffsetDateTime
import java.util.stream.Collectors
import kotlin.io.path.name

/**
 * Filesystem-backed [DocumentContentStore]. Documents keep their existing
 * `documents/{key}` layout with a `.meta` sidecar for the content type.
 *
 * The filesystem has no native TTL, so this backend implements
 * [ContentRetentionMaintainer]: the content reaper drives an age-based sweep that
 * deletes document files (and their sidecars) older than the retention window. The
 * file's modification time is stamped to the owning document's `created_at` on write,
 * so the sweep's age basis is exact even for backdated content.
 */
class FilesystemDocumentContentStore(
    private val basePath: Path,
) : DocumentContentStore,
    ContentRetentionMaintainer {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Root under which document blobs live — the sweep is scoped to this subtree. */
    private val documentsRoot: Path = basePath.resolve(DOCUMENTS_PREFIX)

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long, createdAt: OffsetDateTime) {
        val file = basePath.resolve(key)
        Files.createDirectories(file.parent)
        content.use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
        Files.writeString(metaPath(file), contentType)
        // Age basis for the sweep — exact even if createdAt is backdated.
        Files.setLastModifiedTime(file, FileTime.from(createdAt.toInstant()))
    }

    override fun get(key: String): StoredContent? {
        val file = basePath.resolve(key)
        if (!Files.exists(file)) return null
        return StoredContent(
            content = Files.newInputStream(file),
            contentType = Files.readString(metaPath(file)),
            sizeBytes = Files.size(file),
        )
    }

    override fun delete(key: String): Boolean {
        val file = basePath.resolve(key)
        if (!Files.exists(file)) return false
        Files.deleteIfExists(metaPath(file))
        return Files.deleteIfExists(file)
    }

    override fun exists(key: String): Boolean = Files.exists(basePath.resolve(key))

    /**
     * Delete document files whose modification time is older than the retention
     * window (plus their `.meta` sidecars). Assets never live under this root, so
     * the sweep can never touch permanent data.
     */
    override fun reclaim(retentionMonths: Int) {
        if (!Files.isDirectory(documentsRoot)) return
        val cutoff = FileTime.from(
            EpistolaClock.offsetDateTime().minusMonths(retentionMonths.toLong()).toInstant(),
        )
        val stale = Files.walk(documentsRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .filter { !it.name.endsWith(META_SUFFIX) }
                .filter { Files.getLastModifiedTime(it) < cutoff }
                .collect(Collectors.toList())
        }
        var deleted = 0
        for (file in stale) {
            try {
                Files.deleteIfExists(metaPath(file))
                if (Files.deleteIfExists(file)) deleted++
            } catch (e: Exception) {
                logger.warn("Failed to sweep stale document blob {}: {}", file, e.message)
            }
        }
        if (deleted > 0) {
            logger.info("Swept {} stale document blob(s) older than {} months from {}", deleted, retentionMonths, documentsRoot)
        }
    }

    private fun metaPath(file: Path): Path = file.resolveSibling("${file.fileName}$META_SUFFIX")

    private companion object {
        const val DOCUMENTS_PREFIX = "documents"
        const val META_SUFFIX = ".meta"
    }
}
