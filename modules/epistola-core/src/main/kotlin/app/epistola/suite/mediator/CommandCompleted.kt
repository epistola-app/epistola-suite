// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.time.EpistolaClock
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
    val occurredAt: Instant = EpistolaClock.instant(),
)
