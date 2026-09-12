// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

/**
 * Repository-wide source access for architecture tests that enforce conventions across
 * all modules, not just this one. The repository root is located by walking up from the
 * test working directory to the directory containing settings.gradle.kts.
 *
 * Guards that resolve their own relative paths silently scan only the module they run in, which
 * is how the UI/REST rule went years covering one of nine modules that ship templates. Reach for
 * one of these accessors rather than `Paths.get(...)`.
 */
internal object RepoSources {

    val repoRoot: Path by lazy {
        var dir = Paths.get("").toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent
                ?: error("Could not locate the repository root (no settings.gradle.kts above ${Paths.get("").toAbsolutePath()})")
        }
        dir
    }

    /** All production Kotlin sources under apps and modules src/main, excluding build output. */
    fun mainKotlinFiles(): List<Path> = sourceFiles { path ->
        path.name.endsWith(".kt") && "/src/main/" in relativize(path)
    }

    /** All Kotlin test sources under apps and modules src/test, excluding build output. */
    fun testKotlinFiles(): List<Path> = sourceFiles { path ->
        path.name.endsWith(".kt") && "/src/test/" in relativize(path)
    }

    /** All YAML/properties config files under apps and modules src (main + test), excluding build output. */
    fun configFiles(): List<Path> = sourceFiles { path ->
        CONFIG_EXTENSIONS.any { path.name.endsWith(it) } && "/src/" in relativize(path)
    }

    /**
     * Everything that reaches the browser: Thymeleaf templates and static JavaScript from any
     * module that contributes UI, plus the editor's TypeScript. Feature modules ship their own
     * templates and static files, so scanning only the host app leaves most of the UI unchecked.
     * Editor unit tests are excluded: they are not code the browser runs in the product.
     */
    fun uiAssetFiles(): List<Path> = sourceFiles { path ->
        val relative = relativize(path)
        when {
            path.name.endsWith(".html") -> "/src/main/resources/templates/" in relative
            path.name.endsWith(".js") -> "/src/main/resources/static/" in relative
            path.name.endsWith(".ts") -> "/src/main/typescript/" in relative && !path.name.endsWith(".test.ts")
            else -> false
        }
    }

    private fun sourceFiles(accept: (Path) -> Boolean): List<Path> = SOURCE_ROOTS
        .map(repoRoot::resolve)
        .filter(Files::exists)
        .flatMap { base ->
            Files.walk(base).use { stream ->
                stream
                    .filter { path ->
                        val relative = relativize(path)
                        "/build/" !in relative && "/node_modules/" !in relative
                    }
                    .filter(accept)
                    .toList()
            }
        }

    private val SOURCE_ROOTS = listOf("apps", "modules")
    private val CONFIG_EXTENSIONS = listOf(".yaml", ".yml", ".properties")

    fun relativize(path: Path): String = repoRoot.relativize(path).toString()

    /**
     * Removes line and block comments while preserving line numbers, so convention
     * checks do not flag banned constructs that are merely discussed in comments.
     * Kotlin, JavaScript and TypeScript share this comment syntax.
     */
    fun stripComments(source: String): String {
        val withoutBlockComments = BLOCK_COMMENT.replace(source) { match ->
            "\n".repeat(match.value.count { it == '\n' })
        }
        return withoutBlockComments.lineSequence().joinToString("\n") { line ->
            LINE_COMMENT.replace(line, "")
        }
    }

    /** The same idea for templates, whose comments are HTML rather than slashes. */
    fun stripHtmlComments(source: String): String = HTML_COMMENT.replace(source) { match ->
        "\n".repeat(match.value.count { it == '\n' })
    }

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""//.*""")
    private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
}
