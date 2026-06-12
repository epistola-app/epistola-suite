package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Enforces that cross-domain (and cross-module) calls go through the mediator.
 *
 * A domain interacts with another domain by dispatching its command/query classes
 * (CreateX(...).execute(), GetY(...).query()), never by importing and invoking the other
 * domain's handler directly. Direct handler calls would bypass the mediator's
 * authorization enforcement, metrics, and event publication.
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

        for (path in RepoSources.mainKotlinFiles()) {
            val source = Files.readString(path)
            val filePackage = packageDeclaration.find(source)?.groupValues?.get(1) ?: continue

            importDeclaration.findAll(source)
                .map { it.groupValues[1] }
                .filter { it in handlerClassNames }
                .filter { it.substringBeforeLast('.') != filePackage }
                .forEach { handler ->
                    violations.add(
                        "${RepoSources.relativize(path)} imports $handler — " +
                            "dispatch the command/query through the mediator instead",
                    )
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Direct mediator-handler imports found:\n${violations.joinToString("\n")}",
        )
    }
}
