package app.epistola.suite.mediator

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for SpringMediator metrics instrumentation.
 *
 * Verifies that command and query execution records Micrometer timers
 * with correct names, tags, and outcomes. Uses SimpleMeterRegistry
 * for fast, Spring-context-free testing.
 */
class SpringMediatorMetricsTest {

    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var mediator: SpringMediator

    // Test commands and queries
    data class GreetCommand(val name: String) : Command<String>
    data class FailingCommand(val message: String) : Command<Unit>
    data class LookupQuery(val id: Int) : Query<String?>
    data class FailingQuery(val reason: String) : Query<Nothing>

    // Handlers
    class GreetHandler : CommandHandler<GreetCommand, String> {
        override fun handle(command: GreetCommand) = "Hello, ${command.name}!"
    }

    class FailingCommandHandler : CommandHandler<FailingCommand, Unit> {
        override fun handle(command: FailingCommand) {
            throw IllegalStateException(command.message)
        }
    }

    class LookupHandler : QueryHandler<LookupQuery, String?> {
        override fun handle(query: LookupQuery) = if (query.id > 0) "found-${query.id}" else null
    }

    class FailingQueryHandler : QueryHandler<FailingQuery, Nothing> {
        override fun handle(query: FailingQuery): Nothing {
            throw IllegalArgumentException(query.reason)
        }
    }

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()

        val context = StaticApplicationContext()
        context.beanFactory.registerSingleton("greetHandler", GreetHandler())
        context.beanFactory.registerSingleton("failingCommandHandler", FailingCommandHandler())
        context.beanFactory.registerSingleton("lookupHandler", LookupHandler())
        context.beanFactory.registerSingleton("failingQueryHandler", FailingQueryHandler())
        context.refresh()

        mediator = SpringMediator(context, context, meterRegistry)
    }

    @Test
    fun `successful command records timer with success outcome`() {
        val result = mediator.send(GreetCommand("World"))
        assertEquals("Hello, World!", result)

        val timer = meterRegistry.find("epistola.mediator.command.duration")
            .tag("command", "GreetCommand")
            .tag("outcome", "success")
            .timer()

        assertNotNull(timer, "Timer should be registered")
        assertEquals(1, timer.count())
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0)
    }

    @Test
    fun `failed command records timer with failure outcome`() {
        assertFailsWith<IllegalStateException> {
            mediator.send(FailingCommand("boom"))
        }

        val timer = meterRegistry.find("epistola.mediator.command.duration")
            .tag("command", "FailingCommand")
            .tag("outcome", "failure")
            .timer()

        assertNotNull(timer, "Timer should be registered for failed command")
        assertEquals(1, timer.count())
    }

    @Test
    fun `successful query records timer with success outcome`() {
        val result = mediator.query(LookupQuery(42))
        assertEquals("found-42", result)

        val timer = meterRegistry.find("epistola.mediator.query.duration")
            .tag("query", "LookupQuery")
            .tag("outcome", "success")
            .timer()

        assertNotNull(timer, "Timer should be registered")
        assertEquals(1, timer.count())
    }

    @Test
    fun `failed query records timer with failure outcome`() {
        assertFailsWith<IllegalArgumentException> {
            mediator.query(FailingQuery("not found"))
        }

        val timer = meterRegistry.find("epistola.mediator.query.duration")
            .tag("query", "FailingQuery")
            .tag("outcome", "failure")
            .timer()

        assertNotNull(timer, "Timer should be registered for failed query")
        assertEquals(1, timer.count())
    }

    @Test
    fun `multiple command executions accumulate in the same timer`() {
        mediator.send(GreetCommand("Alice"))
        mediator.send(GreetCommand("Bob"))
        mediator.send(GreetCommand("Charlie"))

        val timer = meterRegistry.find("epistola.mediator.command.duration")
            .tag("command", "GreetCommand")
            .tag("outcome", "success")
            .timer()

        assertNotNull(timer)
        assertEquals(3, timer.count())
    }

    @Test
    fun `different command types get separate timers`() {
        mediator.send(GreetCommand("Alice"))
        assertFailsWith<IllegalStateException> {
            mediator.send(FailingCommand("fail"))
        }

        val greetTimer = meterRegistry.find("epistola.mediator.command.duration")
            .tag("command", "GreetCommand")
            .timer()
        val failTimer = meterRegistry.find("epistola.mediator.command.duration")
            .tag("command", "FailingCommand")
            .timer()

        assertNotNull(greetTimer)
        assertNotNull(failTimer)
        assertEquals(1, greetTimer.count())
        assertEquals(1, failTimer.count())
    }
}
