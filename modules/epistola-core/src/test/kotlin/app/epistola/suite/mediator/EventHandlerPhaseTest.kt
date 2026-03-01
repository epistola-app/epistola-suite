package app.epistola.suite.mediator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for EventHandler phase behavior (IMMEDIATE vs AFTER_COMMIT).
 *
 * Focus: Verifying the contract of EventPhase enum and EventHandler interface.
 *
 * Integration tests in EventPersistenceIntegrationTest verify actual mediator behavior
 * with Spring context and transaction listeners.
 */
class EventHandlerPhaseTest {

    @Test
    fun `IMMEDIATE phase represents synchronous execution`() {
        val phase = EventPhase.IMMEDIATE
        assertEquals("IMMEDIATE", phase.name)
    }

    @Test
    fun `AFTER_COMMIT phase represents asynchronous execution`() {
        val phase = EventPhase.AFTER_COMMIT
        assertEquals("AFTER_COMMIT", phase.name)
    }

    @Test
    fun `phases are distinct`() {
        assertTrue(EventPhase.IMMEDIATE != EventPhase.AFTER_COMMIT)
    }

    @Test
    fun `event handler can have custom phase`() {
        val immediateHandler = ImmediateTestHandler()
        val afterCommitHandler = AfterCommitTestHandler()

        assertEquals(EventPhase.IMMEDIATE, immediateHandler.phase)
        assertEquals(EventPhase.AFTER_COMMIT, afterCommitHandler.phase)
    }

    @Test
    fun `event handler phase can be overridden`() {
        val handler = CustomPhaseHandler(EventPhase.IMMEDIATE)
        assertEquals(EventPhase.IMMEDIATE, handler.phase)

        val handler2 = CustomPhaseHandler(EventPhase.AFTER_COMMIT)
        assertEquals(EventPhase.AFTER_COMMIT, handler2.phase)
    }

    // Test data
    data class TestCommand(val value: String) : Command<String>

    class ImmediateTestHandler : EventHandler<TestCommand> {
        override val phase = EventPhase.IMMEDIATE

        override fun on(event: TestCommand, result: Any?) {}
    }

    class AfterCommitTestHandler : EventHandler<TestCommand> {
        override val phase = EventPhase.AFTER_COMMIT

        override fun on(event: TestCommand, result: Any?) {}
    }

    class CustomPhaseHandler(override val phase: EventPhase) : EventHandler<TestCommand> {
        override fun on(event: TestCommand, result: Any?) {}
    }
}
