package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Enforces that all button classes in Thymeleaf templates use the design
 * system (`ep-btn` / `ep-btn-*`) instead of bare `btn` / `btn-*` conventions.
 */
class ButtonClassComplianceTest {

    @Test
    fun `templates must use ep-btn design system classes`() {
        val templateDir = Paths.get("src/main/resources/templates")
        val violations = mutableListOf<String>()

        val allowedPattern = Regex("""^ep-btn(-.*)?$""")
        val disallowedPattern = Regex("""\bbtn\b|\bbtn-""")

        val classPatterns = listOf(
            // HTML class attribute (also catches class="..." inside JS string literals)
            Regex("""class=["']([^"']+)["']"""),
            // Thymeleaf class replacement / append
            Regex("""th:class=["']([^"']+)["']"""),
            Regex("""th:classappend=["']([^"']+)["']"""),
            // JS className assignment
            Regex("""className\s*=\s*["']([^"']+)["']"""),
            // JS classList manipulation
            Regex("""classList\.(?:add|remove|toggle)\s*\(\s*["']([^"']+)["']"""),
        )

        if (Files.exists(templateDir)) {
            Files.walk(templateDir)
                .filter { it.toString().endsWith(".html") }
                .forEach { path ->
                    val relativePath = templateDir.relativize(path)
                    val lines = Files.readAllLines(path)

                    lines.forEachIndexed { index, line ->
                        val lineNo = index + 1
                        classPatterns.forEach { pattern ->
                            pattern.findAll(line).forEach { match ->
                                val rawValue = match.groupValues[1]
                                val tokens = rawValue.split(Regex("""\s+"""))
                                tokens.forEach { rawToken ->
                                    val token = rawToken.trim('\'', '"')
                                    if (disallowedPattern.containsMatchIn(token) &&
                                        !allowedPattern.matches(token)
                                    ) {
                                        violations.add(
                                            "$relativePath:$lineNo — '$token' in '$rawValue'",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Found bare 'btn' class names in templates (use 'ep-btn' design system instead):\n${violations.joinToString("\n")}",
        )
    }
}
