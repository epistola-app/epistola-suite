package app.epistola.suite.architecture

import app.epistola.suite.architecture.RepoSources.relativize
import app.epistola.suite.architecture.RepoSources.repoRoot
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build-time gate ensuring every icon a Thymeleaf template references actually
 * exists in the generated design-system sprite. Plain unit test — no Spring, no
 * Docker — so it runs in the fast `unitTest` cycle and gates every PR (same
 * posture as [UiTestHygieneTest]).
 *
 * Why this exists: the icon fragment
 * (`apps/epistola/src/main/resources/templates/fragments/icon.html`) renders
 * `<use href="/design-system/icons.svg#icon-{name}">`. If `{name}` is not a
 * `<symbol>` in `modules/design-system/icons.svg`, the browser silently renders
 * nothing — a blank icon. The sprite is built from a hand-maintained allow-list
 * (`modules/design-system/icons/generate-sprite.js`), so a template can
 * reference an icon that was never added to the list, or a Lucide name that
 * does not exist. Neither Thymeleaf nor the build catches it. This test does.
 *
 * Every icon usage in the codebase passes a single-quoted string literal
 * (`icon(name='file-text', ...)`), so the names are fully statically
 * resolvable; there are no dynamic `${...}` icon names to special-case.
 */
class IconUsageTest {

    /** Captures the literal name in `~{fragments/icon :: icon(name='foo', ...)}`. */
    private val iconUsage = Regex("""icon\s*\(\s*name\s*=\s*'([^']+)'""")

    /** Captures `foo` from `<symbol id="icon-foo" ...>` in the sprite. */
    private val spriteSymbol = Regex("""id="icon-([^"]+)"""")

    private val spritePath: Path = repoRoot.resolve("modules/design-system/icons.svg")

    @Test
    fun `every icon used in a template exists in the sprite`() {
        val sprite = spriteIcons()
        assertTrue(
            sprite.size >= 20,
            "Parsed only ${sprite.size} symbols from $spritePath — the sprite parser likely regressed.",
        )

        val violations = sortedSetOf<String>()
        var usageCount = 0
        for (template in templateFiles()) {
            val text = Files.readString(template)
            for (name in usedIconNames(text)) {
                usageCount++
                if (name !in sprite) {
                    violations += "${relativize(template)}: icon '$name' is not in the sprite"
                }
            }
        }

        // The app renders ~110 icons; if the scanner suddenly sees almost none,
        // the usage marker has broken and the test would pass for the wrong
        // reason. Fail loudly instead of going green on an empty scan.
        assertTrue(
            usageCount >= 80,
            "Matched only $usageCount icon usages — expected 80+. The usage marker likely " +
                "regressed; this test must not pass on an empty scan.",
        )

        assertTrue(
            violations.isEmpty(),
            "Templates reference icons missing from modules/design-system/icons.svg.\n" +
                "Add each name to the ICONS array in " +
                "modules/design-system/icons/generate-sprite.js, then run " +
                "`pnpm --filter @epistola/design-system generate:icons`:\n\n" +
                violations.joinToString("\n"),
        )
    }

    /**
     * Negative self-test: proves the usage parser actually extracts the right
     * names (and is not fooled by the word "icon" in prose), so the main test
     * cannot pass on a parser that silently matches nothing.
     */
    @Test
    fun `usage parser captures literal icon names and ignores prose`() {
        val sample = """
            <th:block th:replace="~{fragments/icon :: icon(name='database', class='ep-icon')}" />
            <th:block th:replace="~{fragments/icon :: icon(name='rotate-ccw', class='ep-icon ep-icon-sm')}" />
            This sentence mentions an icon but icon(name='plus') is the only real call after it.
        """.trimIndent()

        assertEquals(listOf("database", "rotate-ccw", "plus"), usedIconNames(sample))
    }

    private fun usedIconNames(text: String): List<String> = iconUsage.findAll(text).map { it.groupValues[1] }.toList()

    private fun spriteIcons(): Set<String> = spriteSymbol.findAll(Files.readString(spritePath)).map { it.groupValues[1] }.toSet()

    /** Every Thymeleaf template under apps/ and modules/, excluding build output. */
    private fun templateFiles(): List<Path> = listOf("apps", "modules")
        .map(repoRoot::resolve)
        .filter(Files::exists)
        .flatMap { base ->
            Files.walk(base).use { stream ->
                stream
                    .filter { it.name.endsWith(".html") }
                    .filter { path ->
                        val relative = relativize(path)
                        "/src/main/resources/templates/" in relative && "/build/" !in relative
                    }
                    .toList()
            }
        }
}
