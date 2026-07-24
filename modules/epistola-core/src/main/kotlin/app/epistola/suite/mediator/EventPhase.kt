// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

/**
 * Determines when an event handler is invoked relative to transaction boundaries.
 *
 * @property IMMEDIATE Invoke immediately after CommandHandler.handle() returns, within the same call stack.
 *     Runs inside the mediator's per-command transaction, so a throwing handler rolls the
 *     command back. (For [SelfManagedTransaction] commands there is no surrounding
 *     transaction and the command's own writes have already committed.)
 *     Use case: Creating dependent entities that must exist.
 *
 * @property AFTER_COMMIT Invoke after the mediator's per-command transaction commits via
 *     Spring's TransactionalEventListener. Failure is isolated: exceptions are logged but
 *     don't affect the command. (For [SelfManagedTransaction] commands these run
 *     immediately at publication — fallbackExecution — since there is no transaction.)
 *     Use case: Cache invalidation, metrics, notifications, audit logging.
 */
enum class EventPhase {
    IMMEDIATE,
    AFTER_COMMIT,
}
