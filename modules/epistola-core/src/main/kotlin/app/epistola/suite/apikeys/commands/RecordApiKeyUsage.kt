// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys.commands

import app.epistola.suite.common.NotAudited
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Records that an API key was used: sets its `last_used_at` to NOW(), at most once a minute.
 * [ApiKeyAuthenticationFilter] dispatches it, best-effort, on every request that authenticates with
 * the key, on the request thread.
 *
 * Written on every request, concurrent requests on one key queued on that key's row lock, which made
 * it most of the database time of an API request under load. The column is read at minute
 * precision (the application page, consumer status), so a write within [RecordApiKeyUsage.WRITE_INTERVAL] of the last
 * one is skipped: the condition is in the UPDATE, so it holds across nodes, and a skipped update
 * neither writes nor waits for a lock.
 *
 * `SystemInternal` because the auth filter dispatches it before user
 * authorization is established for the request.
 */
data class RecordApiKeyUsage(
    val id: ApiKeyKey,
) : Command<Unit>,
    SystemInternal,
    NotAudited {
    companion object {
        /** The precision `last_used_at` is kept at. */
        const val WRITE_INTERVAL = "1 minute"
    }
}

@Component
class RecordApiKeyUsageHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RecordApiKeyUsage, Unit> {
    override fun handle(command: RecordApiKeyUsage) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE api_keys SET last_used_at = NOW()
                WHERE id = :id AND (last_used_at IS NULL OR last_used_at < NOW() - INTERVAL '${RecordApiKeyUsage.WRITE_INTERVAL}')
                """,
            )
                .bind("id", command.id)
                .execute()
        }
    }
}
