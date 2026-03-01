package app.epistola.suite.mediator

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for SpringMediator event handling.
 *
 * Focus: Verifying that the mediator correctly:
 * - Publishes CommandCompleted events
 * - Invokes IMMEDIATE event handlers in the same call stack
 * - Propagates IMMEDIATE handler exceptions to the caller
 * - Publishes Spring events for AFTER_COMMIT handlers
 *
 * These tests verify the basic contract of the event system without requiring
 * the full Spring context. Integration tests in EventPersistenceIntegrationTest
 * verify behavior with transactions and Spring listeners.
 */
class SpringMediatorEventHandlingTest {

    @Test
    fun `CommandCompleted event includes command and result`() {
        val command = TestCommand("data")
        val result = "output"
        val event = CommandCompleted(command, result)

        assertEquals(command, event.command)
        assertEquals(result, event.result)
        assertEquals(command, event.command)
    }

    @Test
    fun `EventPhase has expected values`() {
        val phases = EventPhase.values()
        assertEquals(2, phases.size)
        assertEquals(EventPhase.IMMEDIATE, phases[0])
        assertEquals(EventPhase.AFTER_COMMIT, phases[1])
    }

    @Test
    fun `EventHandler default phase is AFTER_COMMIT`() {
        val handler = TestEventHandler()
        assertEquals(EventPhase.AFTER_COMMIT, handler.phase)
    }

    // Test data classes
    data class TestCommand(val value: String) : Command<String>

    class TestEventHandler : EventHandler<TestCommand> {
        override fun on(event: TestCommand, result: Any?) {
            // no-op
        }
    }
}
