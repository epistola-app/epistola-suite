package app.epistola.suite.handlers

import app.epistola.suite.changelog.ChangelogEntry
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

@Component
class ChangelogRenderer {
    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder()
        .escapeHtml(true)
        .build()

    private val entries: List<ChangelogEntry> by lazy { parseEntries() }

    fun entries(): List<ChangelogEntry> = entries

    private fun parseEntries(): List<ChangelogEntry> {
        val resource = ClassPathResource("changelog/CHANGELOG.md")
        if (!resource.exists()) return emptyList()

        val markdown = resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val versionPattern = Regex("""^## \[(\d+\.\d+\.\d+)] - (\d{4}-\d{2}-\d{2})""", RegexOption.MULTILINE)
        val matches = versionPattern.findAll(markdown).toList()

        return matches.mapIndexed { index, match ->
            val version = match.groupValues[1]
            val date = match.groupValues[2]
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else markdown.length
            val sectionMarkdown = markdown.substring(start, end).trim()
            val html = renderer.render(parser.parse(sectionMarkdown))
            ChangelogEntry(version = version, date = date, html = html)
        }
    }
}
