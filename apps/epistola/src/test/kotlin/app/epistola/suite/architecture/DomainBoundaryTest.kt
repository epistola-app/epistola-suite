// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name
import kotlin.test.assertEquals
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

    /**
     * The walk runs while other build tasks write into `build/` and delete from it, so it must never
     * enter one: a file listed there can be gone by the time it is read (`NoSuchFileException`, as on
     * CI). An unreadable build directory stands in for one that changes under the walk.
     */
    @Test
    fun `the source walk never enters build output or installed dependencies`(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("app/src/main/kotlin")).resolve("A.kt")
        Files.writeString(source, "package a")
        val busy = Files.createDirectories(root.resolve("app/build/kotlin/caches"))
        Files.writeString(busy.resolve("gone.tab"), "")
        busy.toFile().setReadable(false)
        Files.writeString(Files.createDirectories(root.resolve("app/node_modules/pkg")).resolve("index.js"), "")
        try {
            assertEquals(listOf(source), filesUnder(root))
        } finally {
            busy.toFile().setReadable(true)
        }
    }

    private fun mainKotlinFiles(): List<Path> = listOf("apps", "modules")
        .map(repoRoot::resolve)
        .filter(Files::exists)
        .flatMap { base ->
            filesUnder(base)
                .filter { it.name.endsWith(".kt") }
                .filter { path -> "/src/main/" in relativize(path) }
        }

    /**
     * Every file under [base], leaving out build output and installed dependencies, whose directories
     * are not entered at all (as `RepoSources.filesUnder` in the guards module, which this module
     * cannot reach).
     */
    internal fun filesUnder(base: Path): List<Path> {
        val files = mutableListOf<Path>()
        Files.walkFileTree(
            base,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = if (dir != base && dir.name in skippedDirectories) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) files.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return files
    }

    private val skippedDirectories = setOf("build", "node_modules", ".gradle")

    private fun relativize(path: Path): String = repoRoot.relativize(path).toString()

    private val repoRoot: Path by lazy {
        var dir = Paths.get("").toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("Could not locate the repository root from ${Paths.get("").toAbsolutePath()}")
        }
        dir
    }
}
