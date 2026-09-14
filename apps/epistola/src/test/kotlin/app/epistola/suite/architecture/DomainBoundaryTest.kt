// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.test.assertTrue

/**
 * Enforces that cross-domain (and cross-module) calls go through the mediator.
 *
 * A domain interacts with another domain by dispatching its command/query classes
 * (CreateX(...).execute(), GetY(...).query()), never by importing and invoking the other
 * domain's handler directly. Direct handler calls would bypass the mediator's
 * authorization enforcement, metrics, and event publication.
 *
 * This guard stays here rather than moving to `modules/guards` with the source scanners: it reads
 * handler names from [MediatorClasspath], which needs the compiled application classpath, so it can
 * never be part of the fast path those scanners gained. It therefore walks the sources itself —
 * `RepoSources` is `internal` to the guards module and does not cross a module boundary.
 */
class DomainBoundaryTest {

    @Test
    fun `mediator handlers are never imported outside their own package`() {
        val handlerClassNames = (MediatorClasspath.commandHandlers + MediatorClasspath.queryHandlers)
            .map { it.name.replace('$', '.') }
            .toSet()

        val packageDeclaration = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
        val importDeclaration = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)
        val violations = mutableListOf<String>()

        for (path in mainKotlinFiles()) {
            val source = Files.readString(path)
            val filePackage = packageDeclaration.find(source)?.groupValues?.get(1) ?: continue

            importDeclaration.findAll(source)
                .map { it.groupValues[1] }
                .filter { it in handlerClassNames }
                .filter { it.substringBeforeLast('.') != filePackage }
                .forEach { handler ->
                    violations.add(
                        "${relativize(path)} imports $handler — " +
                            "dispatch the command/query through the mediator instead",
                    )
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Direct mediator-handler imports found:\n${violations.joinToString("\n")}",
        )
    }

    private fun mainKotlinFiles(): List<Path> = listOf("apps", "modules")
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

    private fun relativize(path: Path): String = repoRoot.relativize(path).toString()

    private val repoRoot: Path by lazy {
        var dir = Paths.get("").toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("Could not locate the repository root from ${Paths.get("").toAbsolutePath()}")
        }
        dir
    }
}
