// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.changelog

import app.epistola.suite.version.SemVersion
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
        val effectiveVersion = if (appVersion == "dev") allEntries.first().version else SemVersion.normalize(appVersion)
        if (lastAcknowledged == effectiveVersion) return false
        return entriesSince(allEntries, lastAcknowledged).isNotEmpty()
    }

    fun effectiveVersion(appVersion: String, allEntries: List<ChangelogEntry>): String = if (appVersion == "dev") allEntries.firstOrNull()?.version ?: "dev" else SemVersion.normalize(appVersion)

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
    fun stripSuffix(version: String): String = SemVersion.normalize(version)

    private fun parseVersion(version: String): SemVersion? = SemVersion.parse(version)

    private fun compareVersions(a: SemVersion, b: SemVersion): Int = a.compareTo(b)
}
