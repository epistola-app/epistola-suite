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
        if (appVersion == "dev") return false
        if (lastAcknowledged == appVersion) return false
        val baseAppVersion = stripSuffix(appVersion)
        if (lastAcknowledged != null && lastAcknowledged == baseAppVersion) return false
        return entriesSince(allEntries, lastAcknowledged).isNotEmpty()
    }

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
