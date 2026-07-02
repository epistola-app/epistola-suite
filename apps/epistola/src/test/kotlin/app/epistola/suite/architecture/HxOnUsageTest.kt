package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Bans `hx-on::*` / `hx-on-*` attributes in Thymeleaf templates.
 *
 * These HTMX event-handler attributes execute their value via `eval()`, which the CSP
 * blocks (`unsafe-eval` is deliberately absent), and Thymeleaf additionally mangles the
 * `::` as a fragment-expression separator. Use an inline `<script>` with
 * `addEventListener` instead — see the Content Security Policy section in CLAUDE.md.
 */
class HxOnUsageTest {

    private val hxOnAttribute = Regex("""\bhx-on[:-]""")

    @Test
    fun `templates do not use hx-on attributes`() {
        val offenders = templateFiles().flatMap { file ->
            file.readText().lineSequence().withIndex()
                .filter { (_, line) -> hxOnAttribute.containsMatchIn(line) }
                .map { (idx, line) -> "${RepoSources.relativize(file)}:${idx + 1}: ${line.trim()}" }
                .toList()
        }

        assert(offenders.isEmpty()) {
            "hx-on attributes are banned (they eval() under a CSP without unsafe-eval; " +
                "use an inline <script> with addEventListener — see CLAUDE.md):\n" +
                offenders.joinToString("\n")
        }
    }

    /** Every template file in the host app and every feature module. */
    private fun templateFiles(): List<Path> = listOf("apps", "modules")
        .map(RepoSources.repoRoot::resolve)
        .filter(Files::isDirectory)
        .flatMap { base ->
            Files.walk(base).use { stream ->
                stream
                    .filter { it.name.endsWith(".html") }
                    .filter { path ->
                        val relative = RepoSources.relativize(path)
                        "/src/main/resources/templates/" in relative && "/build/" !in relative && "/node_modules/" !in relative
                    }
                    .toList()
            }
        }
}
