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

    /**
     * Strips only the dev/build marker (`-SNAPSHOT`). The SemVer pre-release identifier (e.g. `-RC2`)
     * is part of the version and is preserved: it must match the version's own changelog section
     * (`[1.0.0-RC2]`) and distinguish one release candidate from the next. So `1.0.0-RC3-SNAPSHOT`
     * → `1.0.0-RC3`, `1.0.0-SNAPSHOT` → `1.0.0`, and `1.0.0-RC2` is left untouched.
     */
    fun stripSuffix(version: String): String = version.removeSuffix("-SNAPSHOT")

    private fun parseVersion(version: String): SemVer? {
        val v = stripSuffix(version)
        val core = v.substringBefore("-").split(".")
        if (core.size != 3) return null
        val numbers = core.map { it.toIntOrNull() ?: return null }
        val pre = v.substringAfter("-", "").let { if (it.isEmpty()) emptyList() else it.split(".") }
        return SemVer(numbers, pre)
    }

    private fun compareVersions(a: SemVer, b: SemVer): Int {
        for (i in 0..2) {
            val cmp = a.core[i].compareTo(b.core[i])
            if (cmp != 0) return cmp
        }
        // Equal X.Y.Z: a final release outranks any of its pre-releases (SemVer §11), and among
        // pre-releases compare identifier-by-identifier — numeric parts numerically, otherwise ASCII.
        if (a.pre.isEmpty() && b.pre.isEmpty()) return 0
        if (a.pre.isEmpty()) return 1
        if (b.pre.isEmpty()) return -1
        for (i in 0 until minOf(a.pre.size, b.pre.size)) {
            val cmp = comparePreReleaseId(a.pre[i], b.pre[i])
            if (cmp != 0) return cmp
        }
        return a.pre.size.compareTo(b.pre.size)
    }

    private fun comparePreReleaseId(a: String, b: String): Int {
        val an = a.toIntOrNull()
        val bn = b.toIntOrNull()
        return when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1 // a numeric identifier ranks below an alphanumeric one
            bn != null -> 1
            else -> a.compareTo(b)
        }
    }

    private data class SemVer(val core: List<Int>, val pre: List<String>)
}
