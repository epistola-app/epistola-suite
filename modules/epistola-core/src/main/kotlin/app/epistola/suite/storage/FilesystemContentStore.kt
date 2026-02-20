package app.epistola.suite.storage

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Filesystem-backed content store.
 *
 * Stores content as files under `{basePath}/{key}` with a `.meta` sidecar
 * file storing the content type. Useful for local development.
 */
class FilesystemContentStore(
    private val basePath: Path,
) : ContentStore {

    override fun put(key: String, content: InputStream, contentType: String, sizeBytes: Long) {
        val file = basePath.resolve(key)
        Files.createDirectories(file.parent)
        content.use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
        Files.writeString(metaPath(file), contentType)
    }

    override fun get(key: String): StoredContent? {
        val file = basePath.resolve(key)
        if (!Files.exists(file)) return null

        val contentType = Files.readString(metaPath(file))
        return StoredContent(
            content = Files.newInputStream(file),
            contentType = contentType,
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

    private fun metaPath(file: Path): Path = file.resolveSibling("${file.fileName}.meta")
}
