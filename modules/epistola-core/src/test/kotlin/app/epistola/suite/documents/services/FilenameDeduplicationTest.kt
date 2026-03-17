package app.epistola.suite.documents.services

import app.epistola.suite.documents.BatchDownloadProperties
import app.epistola.suite.storage.ContentStore
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class FilenameDeduplicationTest {

    private val service = BatchAssemblyService(
        jdbi = mock(Jdbi::class.java),
        contentStore = mock(ContentStore::class.java),
        properties = BatchDownloadProperties(),
    )

    @Test
    fun `unique filenames are unchanged`() {
        val used = mutableMapOf<String, Int>()
        assertThat(service.deduplicateFilename("letter.pdf", used)).isEqualTo("letter.pdf")
        assertThat(service.deduplicateFilename("invoice.pdf", used)).isEqualTo("invoice.pdf")
    }

    @Test
    fun `duplicate filenames get numeric suffix`() {
        val used = mutableMapOf<String, Int>()
        assertThat(service.deduplicateFilename("letter.pdf", used)).isEqualTo("letter.pdf")
        assertThat(service.deduplicateFilename("letter.pdf", used)).isEqualTo("letter (2).pdf")
        assertThat(service.deduplicateFilename("letter.pdf", used)).isEqualTo("letter (3).pdf")
    }

    @Test
    fun `deduplication works with different extensions`() {
        val used = mutableMapOf<String, Int>()
        assertThat(service.deduplicateFilename("report.pdf", used)).isEqualTo("report.pdf")
        assertThat(service.deduplicateFilename("report.pdf", used)).isEqualTo("report (2).pdf")
        assertThat(service.deduplicateFilename("report.zip", used)).isEqualTo("report.zip")
    }

    @Test
    fun `deduplication works with no extension`() {
        val used = mutableMapOf<String, Int>()
        assertThat(service.deduplicateFilename("readme", used)).isEqualTo("readme")
        assertThat(service.deduplicateFilename("readme", used)).isEqualTo("readme (2)")
    }

    @Test
    fun `deduplication works with multiple dots in filename`() {
        val used = mutableMapOf<String, Int>()
        assertThat(service.deduplicateFilename("my.report.pdf", used)).isEqualTo("my.report.pdf")
        assertThat(service.deduplicateFilename("my.report.pdf", used)).isEqualTo("my.report (2).pdf")
    }
}
