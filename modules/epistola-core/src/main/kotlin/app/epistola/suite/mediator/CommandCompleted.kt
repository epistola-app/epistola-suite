package app.epistola.suite.mediator

import java.time.Instant

/**
 * Spring event published after every successful command execution.
 * Wraps the command and its result for event subscribers.
 *
 * This event is published:
 * - After the CommandHandler.handle() returns successfully
 * - Before returning the result to the caller
 *
 * Event subscribers can react using EventHandler<C> beans or Spring's @EventListener/@TransactionalEventListener.
 */
data class CommandCompleted<C : Command<*>>(
    val command: C,
    val result: Any?,
    val occurredAt: Instant = Instant.now(),
)
