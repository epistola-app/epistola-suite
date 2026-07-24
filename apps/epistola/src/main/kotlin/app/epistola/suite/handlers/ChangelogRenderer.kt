// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.changelog.ChangelogAudience
import app.epistola.suite.changelog.ChangelogEntry
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses the bundled CHANGELOG.md and renders it for the in-app changelog dialog, filtered by audience,
 * change type, and scope.
 *
 * Two entry formats are supported side by side:
 *  - **Commit-style** (current convention, used by `[Unreleased]` and going forward): each bullet is
 *    `- [**[audience]** ]type(scope): **Title.** …` with no `### ` subsection headers. `type` is a
 *    conventional-commit type and `scope` is required.
 *  - **Legacy** (older released versions): Keep-a-Changelog `### Added/Changed/Fixed/Removed` sections
 *    with plain `- [**[audience]** ]**Title.** …` bullets. The type is mapped from the section heading
 *    and the entry has no scope.
 */
@Component
class ChangelogRenderer {
    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder()
        .escapeHtml(true)
        .build()

    /** Parsed once from the bundled CHANGELOG.md; rendered per requested filter on demand. */
    private val versions: List<ParsedVersion> by lazy { parseVersions(loadMarkdown()) }

    /** Rendered, filtered entries memoized per filter combination. */
    private val rendered = ConcurrentHashMap<RenderKey, List<ChangelogEntry>>()

    /**
     * Returns the changelog entries visible under the given filters, newest first. Versions whose every
     * entry is filtered out are dropped. [view] defaults to [ChangelogAudience.ALL]; [type] (a
     * conventional-commit type) and [scope] each default to null = no filter. When [includeUnreleased]
     * is true the `[Unreleased]` section is rendered first as an "Upcoming" entry (`released = false`).
     */
    fun entries(
        view: ChangelogAudience = ChangelogAudience.ALL,
        includeUnreleased: Boolean = false,
        type: String? = null,
        scope: String? = null,
    ): List<ChangelogEntry> = rendered.getOrPut(RenderKey(view, includeUnreleased, type, scope)) { render(versions, view, includeUnreleased, type, scope) }

    /** Conventional-commit types present (audience-filtered), in canonical order — for the type filter. */
    fun availableTypes(view: ChangelogAudience = ChangelogAudience.ALL, includeUnreleased: Boolean = false): List<String> = availableTypes(versions, view, includeUnreleased)

    /** Distinct scopes present (audience-filtered), sorted — for the scope filter. Empty until entries carry scopes. */
    fun availableScopes(view: ChangelogAudience = ChangelogAudience.ALL, includeUnreleased: Boolean = false): List<String> = availableScopes(versions, view, includeUnreleased)

    // ── Test seams: parse + render arbitrary markdown (production reads the bundled file) ──────────────

    internal fun entriesFrom(
        markdown: String,
        view: ChangelogAudience = ChangelogAudience.ALL,
        includeUnreleased: Boolean = false,
        type: String? = null,
        scope: String? = null,
    ): List<ChangelogEntry> = render(parseVersions(markdown), view, includeUnreleased, type, scope)

    internal fun availableTypesFrom(markdown: String, view: ChangelogAudience = ChangelogAudience.ALL, includeUnreleased: Boolean = false): List<String> = availableTypes(parseVersions(markdown), view, includeUnreleased)

    internal fun availableScopesFrom(markdown: String, view: ChangelogAudience = ChangelogAudience.ALL, includeUnreleased: Boolean = false): List<String> = availableScopes(parseVersions(markdown), view, includeUnreleased)

    // ── Rendering ─────────────────────────────────────────────────────────────────────────────────────

    private fun render(versions: List<ParsedVersion>, view: ChangelogAudience, includeUnreleased: Boolean, type: String?, scope: String?): List<ChangelogEntry> = versions
        .filter { includeUnreleased || it.released }
        .mapNotNull { version ->
            val html = renderHtml(version, view, type, scope)
            if (html.isBlank()) return@mapNotNull null
            ChangelogEntry(version = version.version, date = version.date, html = html, summary = buildSummary(version, view, type, scope), released = version.released)
        }

    private fun renderHtml(version: ParsedVersion, view: ChangelogAudience, type: String?, scope: String?): String {
        val visible = version.items.filter { it.matches(view, type, scope) }
        if (visible.isEmpty()) return ""

        val byType = LinkedHashMap<String, MutableList<ParsedItem>>()
        visible.forEach { byType.getOrPut(it.type) { mutableListOf() }.add(it) }

        val sb = StringBuilder()
        // The release summary (leading prose under the version heading) renders above the entries.
        if (version.intro.isNotBlank()) {
            sb.append("""<div class="changelog-summary">""").append(renderer.render(parser.parse(version.intro))).append("</div>\n")
        }
        byType.entries.sortedBy { typeOrder(it.key) }.forEach { (itemType, typeItems) ->
            sb.append("<h3>").append(escapeHtml(friendlyHeading(itemType))).append("</h3>\n")
            sb.append("<ul>\n")
            typeItems.forEach { item ->
                sb.append(withChips(renderItemBody(item.markdown), item.audience, item.scopes)).append("\n")
            }
            sb.append("</ul>\n")
        }
        return sb.toString().trim()
    }

    /** Renders a single bullet's markdown and strips CommonMark's `<ul>…</ul>` envelope, leaving the `<li>…</li>`. */
    private fun renderItemBody(markdown: String): String {
        var body = renderer.render(parser.parse(markdown)).trim()
        if (body.startsWith("<ul>")) body = body.removePrefix("<ul>").trim()
        if (body.endsWith("</ul>")) body = body.removeSuffix("</ul>").trim()
        return body
    }

    /** Injects audience and scope chips immediately after the item's opening `<li>`. */
    private fun withChips(itemHtml: String, audience: ChangelogAudience, scopes: List<String>): String {
        val chips = buildString {
            audienceChip(audience)?.let { append(it) }
            scopes.forEach { append(scopeChip(it)) }
        }
        if (chips.isEmpty()) return itemHtml
        val index = itemHtml.indexOf("<li>")
        if (index < 0) return itemHtml
        val insertAt = index + "<li>".length
        return itemHtml.substring(0, insertAt) + chips + " " + itemHtml.substring(insertAt)
    }

    private fun audienceChip(audience: ChangelogAudience): String? = when (audience) {
        ChangelogAudience.USER -> """<span class="changelog-badge changelog-badge--user">User</span>"""
        ChangelogAudience.DEVELOPER -> """<span class="changelog-badge changelog-badge--dev">Dev</span>"""
        else -> null
    }

    private fun scopeChip(scope: String): String = """<span class="changelog-badge changelog-badge--scope">${escapeHtml(scope)}</span>"""

    private fun buildSummary(version: ParsedVersion, view: ChangelogAudience, type: String?, scope: String?): String {
        val counts = version.items.filter { it.matches(view, type, scope) }.groupingBy { it.type }.eachCount()
        val others = counts.filterKeys { it != "feat" && it != "fix" }.values.sum()

        val parts = mutableListOf<String>()
        counts["feat"]?.let { parts.add(pluralize(it, "new feature", "new features")) }
        counts["fix"]?.let { parts.add(pluralize(it, "fix", "fixes")) }
        if (others > 0) parts.add(pluralize(others, "change", "changes"))

        return parts.joinToString(", ").ifEmpty { "Updates" }
    }

    // ── Filter option discovery ─────────────────────────────────────────────────────────────────────

    private fun availableTypes(versions: List<ParsedVersion>, view: ChangelogAudience, includeUnreleased: Boolean): List<String> {
        val present = visibleItems(versions, view, includeUnreleased).map { it.type }.toSet()
        return CANON_TYPES.filter { it in present } + present.filter { it !in CANON_TYPES }.sorted()
    }

    private fun availableScopes(versions: List<ParsedVersion>, view: ChangelogAudience, includeUnreleased: Boolean): List<String> = visibleItems(versions, view, includeUnreleased).flatMap { it.scopes }.distinct().sorted()

    private fun visibleItems(versions: List<ParsedVersion>, view: ChangelogAudience, includeUnreleased: Boolean): List<ParsedItem> = versions
        .filter { includeUnreleased || it.released }
        .flatMap { it.items }
        .filter { view.shows(it.audience) }

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────────────

    private fun loadMarkdown(): String {
        val resource = ClassPathResource("changelog/CHANGELOG.md")
        if (!resource.exists()) return ""
        return resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun parseVersions(markdown: String): List<ParsedVersion> {
        if (markdown.isBlank()) return emptyList()

        // Accept an optional SemVer pre-release suffix (e.g. 1.0.0-RC2, 1.0.0-beta.1) so
        // release-candidate sections are not silently dropped from the dialog.
        val versionPattern = Regex("""^## \[(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)] - (\d{4}-\d{2}-\d{2})""", RegexOption.MULTILINE)
        val matches = versionPattern.findAll(markdown).toList()

        val result = mutableListOf<ParsedVersion>()

        // The in-progress [Unreleased] section runs from its header to the first released version (or EOF).
        Regex("""^## \[Unreleased]""", RegexOption.MULTILINE).find(markdown)?.let { unreleased ->
            val start = unreleased.range.last + 1
            val end = matches.firstOrNull()?.range?.first ?: markdown.length
            val sectionMarkdown = markdown.substring(start, end).trim()
            val items = parseItems(sectionMarkdown)
            if (items.isNotEmpty()) result.add(ParsedVersion(version = "Unreleased", date = "", released = false, intro = introOf(sectionMarkdown), items = items))
        }

        matches.mapIndexedTo(result) { index, match ->
            val version = match.groupValues[1]
            val date = match.groupValues[2]
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else markdown.length
            val sectionMarkdown = markdown.substring(start, end).trim()
            ParsedVersion(version = version, date = date, released = true, intro = introOf(sectionMarkdown), items = parseItems(sectionMarkdown))
        }

        return result
    }

    /**
     * Splits a version section into individual top-level bullet items, each carrying its conventional-commit
     * type, scope, and audience. A new item starts at a column-0 `- ` bullet and runs until the next such
     * bullet, the next `### ` heading, or the end of the section. Fenced code blocks are tracked so a `- `
     * line inside a code sample does not split an item. Legacy `### ` headings set the fallback type for
     * bullets that carry no `type(scope):` prefix.
     */
    /** The optional release-summary prose: everything from the version heading up to the first bullet or `### ` heading. */
    private fun introOf(section: String): String = section.lineSequence().takeWhile { !it.startsWith("- ") && !it.startsWith("### ") }.joinToString("\n").trim()

    private fun parseItems(section: String): List<ParsedItem> {
        val items = mutableListOf<ParsedItem>()
        var legacyType = "chore"
        var inFence = false

        var buffer: MutableList<String>? = null
        var bufferType = "chore"
        var bufferScopes: List<String> = emptyList()
        var bufferAudience = ChangelogAudience.EVERYONE

        fun flush() {
            val lines = buffer ?: return
            val text = lines.joinToString("\n").trimEnd()
            if (text.isNotBlank()) items.add(ParsedItem(type = bufferType, scopes = bufferScopes, audience = bufferAudience, markdown = text))
            buffer = null
        }

        for (line in section.lines()) {
            val isFence = line.trimStart().startsWith("```")
            if (!inFence) {
                if (line.startsWith("### ")) {
                    flush()
                    legacyType = mapCategory(line.removePrefix("### ").trim())
                    continue
                }
                if (line.startsWith("- ")) {
                    flush()
                    val marker = parseMarkers(line)
                    buffer = mutableListOf(marker.line)
                    bufferType = marker.type ?: legacyType
                    bufferScopes = marker.scopes
                    bufferAudience = marker.audience
                    continue
                }
            }
            if (isFence) inFence = !inFence
            buffer?.add(line)
        }
        flush()
        return items
    }

    /** Strips a leading `**[user]**`/`**[dev]**` badge and a `type(scope):` prefix off a bullet, returning what it found. */
    private fun parseMarkers(line: String): MarkerParse {
        var working = line
        var audience = ChangelogAudience.EVERYONE
        audiencePattern.find(working)?.let { m ->
            ChangelogAudience.entryFromMarker(m.groupValues[2])?.let {
                audience = it
                working = m.groupValues[1] + working.substring(m.range.last + 1)
            }
        }
        var type: String? = null
        var scopes: List<String> = emptyList()
        commitPattern.find(working)?.let { m ->
            type = m.groupValues[2]
            scopes = m.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            working = m.groupValues[1] + working.substring(m.range.last + 1)
        }
        return MarkerParse(audience, type, scopes, working)
    }

    private fun mapCategory(category: String): String = when (category.lowercase()) {
        "added" -> "feat"
        "fixed" -> "fix"
        "changed" -> "refactor"
        "removed" -> "chore"
        "security" -> "fix"
        "deprecated" -> "chore"
        "performance" -> "perf"
        "docs", "documentation" -> "docs"
        "tests" -> "test"
        else -> "chore"
    }

    private fun typeOrder(type: String): Int = CANON_TYPES.indexOf(type).let { if (it < 0) CANON_TYPES.size else it }

    private fun friendlyHeading(type: String): String = when (type) {
        "feat" -> "Features"
        "fix" -> "Fixes"
        "perf" -> "Performance"
        "refactor" -> "Refactoring"
        "docs" -> "Documentation"
        "test" -> "Tests"
        "build" -> "Build"
        "ci" -> "CI"
        "chore" -> "Chores"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    private fun pluralize(count: Int, singular: String, plural: String): String = if (count == 1) "1 $singular" else "$count $plural"

    private fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun ParsedItem.matches(view: ChangelogAudience, type: String?, scope: String?): Boolean = view.shows(audience) &&
        (type == null || this.type.equals(type, ignoreCase = true)) &&
        (scope == null || scopes.any { it.equals(scope, ignoreCase = true) })

    private data class ParsedVersion(val version: String, val date: String, val released: Boolean, val intro: String, val items: List<ParsedItem>)

    private data class ParsedItem(val type: String, val scopes: List<String>, val audience: ChangelogAudience, val markdown: String)

    private data class MarkerParse(val audience: ChangelogAudience, val type: String?, val scopes: List<String>, val line: String)

    private data class RenderKey(val view: ChangelogAudience, val includeUnreleased: Boolean, val type: String?, val scope: String?)

    private companion object {
        val CANON_TYPES = listOf("feat", "fix", "perf", "refactor", "docs", "test", "build", "ci", "chore")
        val audiencePattern = Regex("""^(\s*-\s+)\*\*\[(\w+)]\*\*\s*""")

        // Scope may be a comma-separated list, e.g. feat(editor,pdf):
        val commitPattern = Regex("""^(\s*-\s+)([a-z]+)\(([a-z0-9][a-z0-9.,/-]*)\):\s+""")
    }
}
