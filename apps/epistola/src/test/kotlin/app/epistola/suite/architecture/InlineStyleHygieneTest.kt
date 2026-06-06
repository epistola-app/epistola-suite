package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.test.assertTrue

class InlineStyleHygieneTest {

    @Test
    fun `Thymeleaf templates must not use inline style attributes`() {
        val violations = findInFiles(
            dir = Paths.get("src/main/resources/templates"),
            glob = "*.html",
            pattern = Regex("""\bstyle="[^"]*""""),
            exemption = { line -> line.contains("th:style") },
        )
        assertTrue(
            violations.isEmpty(),
            "Inline style violations in HTML templates — use CSS classes:\n\n${violations.joinToString("\n\n")}",
        )
    }

    @Test
    fun `TypeScript files must not use static inline style attributes`() {
        val violations = findInFiles(
            dir = Paths.get("../../modules/editor/src/main/typescript"),
            glob = "*.ts",
            pattern = Regex("""style="[^"]*""""),
            exemption = { line -> line.contains("\${") },
        )
        assertTrue(
            violations.isEmpty(),
            "Static inline style violations in TypeScript — use CSS classes:\n\n${violations.joinToString("\n\n")}",
        )
    }

    @Test
    fun `HTML templates must not have duplicate class attributes`() {
        val violations = findInFiles(
            dir = Paths.get("src/main/resources/templates"),
            glob = "*.html",
            pattern = Regex("""class="[^"]*"[^>]*class=""""),
        )
        assertTrue(
            violations.isEmpty(),
            "Duplicate class attribute violations — Thymeleaf XML parser rejects these with 500:\n\n${violations.joinToString("\n\n")}",
        )
    }

    @Test
    fun `CSS must not use design-token fallback values`() {
        val violations = findInFiles(
            dir = Paths.get("../../modules"),
            glob = "*.css",
            pattern = Regex("""var\(--ep-[\w-]+,\s*[^)]+\)"""),
        )
        assertTrue(
            violations.isEmpty(),
            "CSS token fallback violations — design system tokens are always loaded, remove fallbacks:\n\n${violations.joinToString("\n\n")}",
        )
    }

    @Test
    fun `TypeScript must not use design-token fallback string literals`() {
        val violations = findInFiles(
            dir = Paths.get("../../modules/editor/src/main/typescript"),
            glob = "*.ts",
            pattern = Regex("""var\(--ep-[\w-]+,\s*[^)]+\)"""),
        )
        assertTrue(
            violations.isEmpty(),
            "TypeScript token fallback violations — design system tokens are always loaded, remove fallbacks:\n\n${violations.joinToString("\n\n")}",
        )
    }

    @Test
    fun `TypeScript must not use runtime style color or background assignments`() {
        val violations = findInFiles(
            dir = Paths.get("../../modules/editor/src/main/typescript"),
            glob = "*.ts",
            pattern = Regex("""style\.(color|background|backgroundColor|borderColor)\s*="""),
            exemption = { line ->
                // Content-driven colors (ProseMirror marks) are exempt — the color comes from
                // the document model, not from a hardcoded design-system value
                line.contains("mark.attrs?.color") ||
                    line.contains("mark.attrs?.background") ||
                    // Variable-assigned color (content-driven) is exempt
                    line.contains("= color;")
            },
        )
        assertTrue(
            violations.isEmpty(),
            "Runtime style color/background violations — use CSS classes or data attributes:\n\n${violations.joinToString("\n\n")}",
        )
    }

    @Test
    fun `TypeScript must not use classList for state-driven styling`() {
        val violations = findInFiles(
            dir = Paths.get("../../modules/editor/src/main/typescript"),
            glob = "*.ts",
            pattern = Regex("""classList\.(add|remove|toggle|contains)"""),
        )
        assertTrue(
            violations.isEmpty(),
            "classList violations — use data attributes for state-driven styling:\n\n${violations.joinToString("\n\n")}",
        )
    }

    @Test
    fun `thstyle must only carry CSS custom properties`() {
        val violations = findInFiles(
            dir = Paths.get("src/main/resources/templates"),
            glob = "*.html",
            pattern = Regex("""th:style="[^"]*"""),
            // Exemption: th:style is allowed only for CSS custom property carriers
            // (--ep-*), which carry data, not raw presentation
            exemption = { line -> line.contains("--ep-") },
        )
        assertTrue(
            violations.isEmpty(),
            "th:style must only carry CSS custom properties (--ep-*), not raw CSS:\n\n${violations.joinToString("\n\n")}",
        )
    }

    private fun findInFiles(
        dir: Path,
        glob: String,
        pattern: Regex,
        exemption: ((String) -> Boolean)? = null,
    ): List<String> {
        assertTrue(Files.exists(dir), "Directory $dir does not exist — path misconfigured")

        val violations = mutableListOf<String>()

        // glob is treated as a file extension (e.g. "*.html" → ".html") for simplicity
        val extension = glob.drop(1)

        Files.walk(dir).use { paths ->
            paths
                .filter { it.toString().endsWith(extension) }
                .filter { path ->
                    val str = path.toString()
                    !str.contains("/dist/") && !str.contains("/build/") && !str.contains("/coverage/") && !str.contains("/test/")
                }
                .filter { !it.toString().endsWith(".test.ts") && !it.toString().endsWith(".spec.ts") }
                .forEach { path: Path ->
                    Files.readAllLines(path).forEachIndexed { idx, line ->
                        val trimmed = line.trimStart()
                        // Skip comment lines — only match start-of-line comment tokens
                        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("<!--")) {
                            return@forEachIndexed
                        }

                        val match = pattern.find(line) ?: return@forEachIndexed
                        if (exemption != null && exemption(line)) return@forEachIndexed

                        violations.add("${path.name}:${idx + 1} — ${line.trim()}")
                    }
                }
        }

        return violations
    }
}
