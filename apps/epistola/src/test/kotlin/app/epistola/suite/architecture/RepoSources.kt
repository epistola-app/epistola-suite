package app.epistola.suite.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

/**
 * Repository-wide source access for architecture tests that enforce conventions across
 * all modules, not just this one. The repository root is located by walking up from the
 * test working directory to the directory containing settings.gradle.kts.
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
    fun mainKotlinFiles(): List<Path> = listOf("apps", "modules")
        .map(repoRoot::resolve)
        .filter(Files::exists)
        .flatMap { base ->
            Files.walk(base).use { stream ->
                stream
                    .filter { it.name.endsWith(".kt") }
                    .filter { path ->
                        val relative = relativize(path)
                        "/src/main/" in relative && "/build/" !in relative && "/node_modules/" !in relative
                    }
                    .toList()
            }
        }

    /** All YAML/properties config files under apps and modules src (main + test), excluding build output. */
    fun configFiles(): List<Path> = listOf("apps", "modules")
        .map(repoRoot::resolve)
        .filter(Files::exists)
        .flatMap { base ->
            Files.walk(base).use { stream ->
                stream
                    .filter { p -> CONFIG_EXTENSIONS.any { p.name.endsWith(it) } }
                    .filter { path ->
                        val relative = relativize(path)
                        "/src/" in relative && "/build/" !in relative && "/node_modules/" !in relative
                    }
                    .toList()
            }
        }

    private val CONFIG_EXTENSIONS = listOf(".yaml", ".yml", ".properties")

    fun relativize(path: Path): String = repoRoot.relativize(path).toString()

    /**
     * Removes line and block comments while preserving line numbers, so convention
     * checks do not flag banned constructs that are merely discussed in comments.
     */
    fun stripComments(source: String): String {
        val withoutBlockComments = BLOCK_COMMENT.replace(source) { match ->
            "\n".repeat(match.value.count { it == '\n' })
        }
        return withoutBlockComments.lineSequence().joinToString("\n") { line ->
            LINE_COMMENT.replace(line, "")
        }
    }

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""//.*""")
}
