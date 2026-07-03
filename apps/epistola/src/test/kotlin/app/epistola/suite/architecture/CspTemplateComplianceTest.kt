package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Enforces the strict-CSP template rules from ADR 0010 (`script-src 'self'`,
 * no `'unsafe-inline'`): the browser will not execute inline scripts or inline
 * event-handler attributes, and a violation fails silently at runtime (console
 * error only) — so the build is where these must be caught.
 *
 * Rules, for every Thymeleaf template in the host app and all feature modules:
 * 1. No executable inline `<script>`: every `<script>` needs `src=` (external,
 *    allowed by `'self'`) or `type="application/json"` (inert data island).
 * 2. No inline `on*=` event-handler attributes — declare a `data-*` hook and
 *    add a delegated listener in a static JS file (see `static/js/behaviors.js`).
 * 3. No `hx-on::*` / `hx-on-*` attributes — they `eval()` their value, and
 *    `unsafe-eval` is deliberately absent from the CSP.
 */
class CspTemplateComplianceTest {

    @Test
    fun `templates contain no executable inline scripts`() {
        val offenders = templateFiles().flatMap { file ->
            val text = file.readText().replace(htmlComment, "")
            scriptTagRegex.findAll(text)
                .filterNot { isAllowedScriptTag(it.value) }
                .map { match ->
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    "${RepoSources.relativize(file)}:$line: ${match.value.replace(Regex("\\s+"), " ").take(120)}"
                }
                .toList()
        }

        assert(offenders.isEmpty()) {
            "Executable inline <script> tags are banned (ADR 0010: script-src 'self' blocks them silently). " +
                "Move behavior to a static JS file with delegated listeners, and pass server data via " +
                "<script type=\"application/json\"> islands:\n" + offenders.joinToString("\n")
        }
    }

    @Test
    fun `templates contain no inline event-handler attributes`() {
        val offenders = templateFiles().flatMap { file ->
            file.readText().lineSequence().withIndex()
                .filter { (_, line) -> onAttributeRegex.containsMatchIn(line) }
                .map { (idx, line) -> "${RepoSources.relativize(file)}:${idx + 1}: ${line.trim().take(120)}" }
                .toList()
        }

        assert(offenders.isEmpty()) {
            "Inline on*= handler attributes are banned (ADR 0010: blocked by the CSP, nonces cannot " +
                "apply to attributes). Declare a data-* hook and handle it with a delegated listener " +
                "in a static JS file (see static/js/behaviors.js):\n" + offenders.joinToString("\n")
        }
    }

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
                "use a delegated listener in a static JS file — see CLAUDE.md):\n" +
                offenders.joinToString("\n")
        }
    }

    private val htmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    private val scriptTagRegex = Regex("""<script\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)

    private fun isAllowedScriptTag(tag: String): Boolean {
        val normalized = tag.replace(Regex("\\s+"), " ")
        val hasSrc = Regex("""\b(th:src|src)\s*=""").containsMatchIn(normalized)
        val isDataIsland = Regex("""\btype\s*=\s*["']application/(json|ld\+json)["']""").containsMatchIn(normalized)
        return hasSrc || isDataIsland
    }

    /**
     * Inline handler attributes. The event-name prefix list avoids false positives on
     * non-event attributes that happen to start with "on".
     */
    private val onAttributeRegex = Regex(
        """\s(?:th:)?on(?:click|dblclick|change|submit|reset|input|invalid|select|load|unload|error|abort|blur|focus|focusin|focusout|key\w*|mouse\w*|drag\w*|drop|touch\w*|pointer\w*|scroll|wheel|copy|cut|paste|contextmenu|toggle|close|cancel|animation\w*|transition\w*|play\w*|pause|ended|volumechange|seek\w*|stalled|suspend|waiting|progress|ratechange|durationchange|loadeddata|loadedmetadata|loadstart|canplay\w*|emptied|storage|message|online|offline|pagehide|pageshow|popstate|hashchange|beforeunload|resize)\s*=""",
        RegexOption.IGNORE_CASE,
    )

    private val hxOnAttribute = Regex("""\bhx-on[:-]""")

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
