package app.epistola.suite.handlers

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

    fun renderHtml(): String {
        val resource = ClassPathResource("changelog/CHANGELOG.md")
        if (!resource.exists()) {
            return "<p class=\"changelog-error\">Changelog is not available.</p>"
        }

        val markdown = resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return renderer.render(parser.parse(markdown))
    }
}
