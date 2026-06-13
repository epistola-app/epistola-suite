package app.epistola.suite.architecture

import app.epistola.suite.security.Authorized
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import kotlin.test.assertTrue

/**
 * Enforces that the mediator's runtime handler discovery cannot fail in production.
 *
 * SpringMediator wires commands/queries to handlers lazily via reflection, so a missing,
 * duplicate, or unregistered handler would only surface when the message is first
 * dispatched. This test performs the same matching at build time across the full
 * application classpath (all feature modules) and fails fast instead.
 */
class MediatorWiringTest {

    @Test
    fun `every command and query has exactly one handler`() {
        val handlersByMessage = (MediatorClasspath.commandHandlers + MediatorClasspath.queryHandlers)
            .groupBy { MediatorClasspath.handledMessageType(it) }

        val violations = (MediatorClasspath.commands + MediatorClasspath.queries).mapNotNull { message ->
            val handlers = handlersByMessage[message].orEmpty()
            when {
                handlers.isEmpty() ->
                    "${message.name} has no CommandHandler/QueryHandler — dispatching it fails at runtime"
                handlers.size > 1 ->
                    "${message.name} has ${handlers.size} handlers (${handlers.joinToString { it.simpleName }}) — " +
                        "SpringMediator picks one in undefined order"
                else -> null
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Mediator wiring problems found:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `every handler is registered as a spring component`() {
        val violations = (MediatorClasspath.commandHandlers + MediatorClasspath.queryHandlers)
            .filterNot { AnnotatedElementUtils.isAnnotated(it, Component::class.java.name) }
            .map { "${it.name} is not annotated with @Component — SpringMediator will not discover it" }

        assertTrue(
            violations.isEmpty(),
            "Unregistered mediator handlers found:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `every command and query declares its authorization`() {
        val violations = (MediatorClasspath.commands + MediatorClasspath.queries)
            .filterNot { Authorized::class.java.isAssignableFrom(it) }
            .map {
                "${it.name} does not implement an Authorized subtype " +
                    "(RequiresPermission / RequiresPlatformRole / RequiresAuthentication / SystemInternal)"
            }

        assertTrue(
            violations.isEmpty(),
            "Commands/queries without an authorization declaration:\n${violations.joinToString("\n")}",
        )
    }
}
