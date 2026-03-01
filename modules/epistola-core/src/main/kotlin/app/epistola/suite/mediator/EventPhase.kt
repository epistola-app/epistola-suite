package app.epistola.suite.mediator

/**
 * Determines when an event handler is invoked relative to transaction boundaries.
 *
 * @property IMMEDIATE Invoke immediately after CommandHandler.handle() returns, within the same call stack.
 *     If the command is @Transactional, this runs inside that transaction.
 *     If the handler throws an exception, it propagates to the caller (may roll back the command).
 *     Use case: Creating dependent entities that must exist.
 *
 * @property AFTER_COMMIT Invoke after the transaction commits via Spring's TransactionalEventListener.
 *     Failure is isolated: exceptions are logged but don't affect the command.
 *     Use case: Cache invalidation, metrics, notifications, audit logging.
 */
enum class EventPhase {
    IMMEDIATE,
    AFTER_COMMIT,
}
