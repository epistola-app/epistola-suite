package app.epistola.suite.storage

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Tag("unit")
class FilesystemContentStoreTest : ContentStoreContractTest() {

    @TempDir
    lateinit var tempDir: Path

    override fun createStore(): ContentStore = FilesystemContentStore(tempDir)
}
