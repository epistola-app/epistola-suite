package app.epistola.suite.changelog

import org.springframework.stereotype.Component

@Component
class ChangelogService {

    fun entriesSince(allEntries: List<ChangelogEntry>, version: String?): List<ChangelogEntry> {
        if (version.isNullOrBlank()) return allEntries
        val parsed = parseVersion(version) ?: return allEntries
        return allEntries.filter { entry ->
            val entryVersion = parseVersion(entry.version)
            entryVersion != null && compareVersions(entryVersion, parsed) > 0
        }
    }

    fun hasUnseenEntries(allEntries: List<ChangelogEntry>, appVersion: String, lastAcknowledged: String?): Boolean {
        if (allEntries.isEmpty()) return false
        // In dev mode, use the latest changelog entry as the effective version
        val effectiveVersion = if (appVersion == "dev") allEntries.first().version else stripSuffix(appVersion)
        if (lastAcknowledged == effectiveVersion) return false
        return entriesSince(allEntries, lastAcknowledged).isNotEmpty()
    }

    fun effectiveVersion(appVersion: String, allEntries: List<ChangelogEntry>): String = if (appVersion == "dev") allEntries.firstOrNull()?.version ?: "dev" else stripSuffix(appVersion)

    fun aggregateSummary(entries: List<ChangelogEntry>): String = when (entries.size) {
        0 -> ""
        1 -> entries.first().summary
        else -> "${entries.sumOf { countItems(it.summary) }} updates across ${entries.size} versions"
    }

    private fun countItems(summary: String): Int = Regex("""(\d+)""").findAll(summary).sumOf { it.value.toIntOrNull() ?: 0 }

    fun stripSuffix(version: String): String = version.substringBefore("-")

    private fun parseVersion(version: String): List<Int>? {
        val base = stripSuffix(version)
        val parts = base.split(".")
        if (parts.size != 3) return null
        return parts.mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val cmp = a[i].compareTo(b[i])
            if (cmp != 0) return cmp
        }
        return 0
    }
}
