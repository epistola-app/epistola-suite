package app.epistola.suite.handlers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class ChangelogRendererTest {

    private val renderer = ChangelogRenderer()

    @Test
    fun `entries parses changelog into structured entries`() {
        val entries = renderer.entries()
        assertThat(entries).isNotEmpty()
        entries.forEach { entry ->
            assertThat(entry.version).matches("\\d+\\.\\d+\\.\\d+")
            assertThat(entry.date).matches("\\d{4}-\\d{2}-\\d{2}")
            assertThat(entry.html).isNotBlank()
        }
    }

    @Test
    fun `entries excludes Unreleased section`() {
        val entries = renderer.entries()
        assertThat(entries.map { it.version }).doesNotContain("Unreleased")
    }

    @Test
    fun `entries are ordered newest first`() {
        val entries = renderer.entries()
        assertThat(entries.size).isGreaterThanOrEqualTo(2)
        val versions = entries.map { it.version }
        // First version should be numerically greater than the second
        assertThat(compareVersions(versions[0], versions[1])).isGreaterThan(0)
    }

    @Test
    fun `entriesSince returns only newer versions`() {
        val allEntries = renderer.entries()
        assertThat(allEntries.size).isGreaterThanOrEqualTo(2)

        val olderVersion = allEntries.last().version
        val since = renderer.entriesSince(olderVersion)

        // Should exclude the version itself and any older
        assertThat(since).hasSizeLessThan(allEntries.size)
        assertThat(since.map { it.version }).doesNotContain(olderVersion)
    }

    @Test
    fun `entriesSince with null returns all entries`() {
        assertThat(renderer.entriesSince(null)).isEqualTo(renderer.entries())
    }

    @Test
    fun `entriesSince with blank returns all entries`() {
        assertThat(renderer.entriesSince("")).isEqualTo(renderer.entries())
    }

    @Test
    fun `entriesSince with latest version returns empty`() {
        val latest = renderer.entries().first().version
        assertThat(renderer.entriesSince(latest)).isEmpty()
    }

    private fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toInt() }
        val bParts = b.split(".").map { it.toInt() }
        for (i in 0..2) {
            val cmp = aParts[i].compareTo(bParts[i])
            if (cmp != 0) return cmp
        }
        return 0
    }
}
