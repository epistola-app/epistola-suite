package app.epistola.suite.handlers

import app.epistola.suite.changelog.ChangelogService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class ChangelogRendererTest {

    private val renderer = ChangelogRenderer()
    private val service = ChangelogService()

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
        assertThat(compareVersions(versions[0], versions[1])).isGreaterThan(0)
    }

    @Test
    fun `entriesSince returns only newer versions`() {
        val allEntries = renderer.entries()
        assertThat(allEntries.size).isGreaterThanOrEqualTo(2)

        val olderVersion = allEntries.last().version
        val since = service.entriesSince(allEntries, olderVersion)

        assertThat(since).hasSizeLessThan(allEntries.size)
        assertThat(since.map { it.version }).doesNotContain(olderVersion)
    }

    @Test
    fun `entriesSince with null returns all entries`() {
        val entries = renderer.entries()
        assertThat(service.entriesSince(entries, null)).isEqualTo(entries)
    }

    @Test
    fun `entriesSince with blank returns all entries`() {
        val entries = renderer.entries()
        assertThat(service.entriesSince(entries, "")).isEqualTo(entries)
    }

    @Test
    fun `entriesSince with latest version returns empty`() {
        val entries = renderer.entries()
        val latest = entries.first().version
        assertThat(service.entriesSince(entries, latest)).isEmpty()
    }

    @Test
    fun `hasUnseenEntries in dev mode uses latest changelog version`() {
        val entries = renderer.entries()
        // Not acknowledged yet — should show
        assertThat(service.hasUnseenEntries(entries, "dev", null)).isTrue()
        // Acknowledged latest — should not show
        val latest = entries.first().version
        assertThat(service.hasUnseenEntries(entries, "dev", latest)).isFalse()
    }

    @Test
    fun `hasUnseenEntries returns false for empty entries`() {
        assertThat(service.hasUnseenEntries(emptyList(), "dev", null)).isFalse()
    }

    @Test
    fun `hasUnseenEntries returns false when already acknowledged`() {
        assertThat(service.hasUnseenEntries(renderer.entries(), "0.12.0", "0.12.0")).isFalse()
    }

    @Test
    fun `hasUnseenEntries handles version suffixes`() {
        val entries = renderer.entries()
        val latest = entries.first().version
        assertThat(service.hasUnseenEntries(entries, "$latest-SNAPSHOT", latest)).isFalse()
    }

    @Test
    fun `hasUnseenEntries returns true when not acknowledged`() {
        val entries = renderer.entries()
        val latest = entries.first().version
        val older = entries.last().version
        assertThat(service.hasUnseenEntries(entries, latest, older)).isTrue()
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
