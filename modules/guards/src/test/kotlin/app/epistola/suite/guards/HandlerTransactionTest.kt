// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mediator runs every command in its transaction, a savepoint when it is dispatched from another
 * command (`SpringMediator.inCommandTransaction`); a command that must manage its own implements
 * `SelfManagedTransaction`. A handler's own `@Transactional` is a second, different transaction
 * boundary on top: it says the handler decides, which it does not, and it hides which of the two
 * applies. So no handler carries one.
 */
class HandlerTransactionTest {
    private val handler = Regex("""\b(CommandHandler|QueryHandler)<""")
    private val transactional = Regex("""@(org\.springframework\.transaction\.annotation\.)?Transactional\b(?!EventListener)""")

    @Test
    fun `no command or query handler opens its own transaction`() {
        val handlers = RepoSources.mainKotlinFiles().filter { handler.containsMatchIn(Files.readString(it)) }
        assertTrue(handlers.size > 100, "handlers not found — the scan would pass vacuously")

        val offenders = handlers
            .filter { transactional.containsMatchIn(RepoSources.stripComments(Files.readString(it))) }
            .map(RepoSources::relativize)

        assertEquals(emptyList(), offenders, "The mediator owns the transaction: drop @Transactional, or implement SelfManagedTransaction")
    }
}
