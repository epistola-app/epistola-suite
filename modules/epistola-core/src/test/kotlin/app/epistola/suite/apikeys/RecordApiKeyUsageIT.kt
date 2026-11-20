// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys

import app.epistola.suite.apikeys.commands.RecordApiKeyUsage
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.createApiKey
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime

/**
 * Every API request records its key's use. Written on every request, concurrent requests on one key
 * queued on that key's row lock; the column is read at minute precision, so it is written at most
 * once a minute.
 */
class RecordApiKeyUsageIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a key's use is written at most once a minute`() {
        val key = withMediator { createApiKey(createTenant("Busy Key").id, "Busy") }

        withMediator { RecordApiKeyUsage(key.apiKey.id).execute() }
        val first = lastUsedAt(key)
        withMediator { RecordApiKeyUsage(key.apiKey.id).execute() }

        assertThat(first).`as`("the first use is recorded").isNotNull()
        assertThat(lastUsedAt(key)).`as`("a second use within the minute writes nothing").isEqualTo(first)
    }

    @Test
    fun `a key last used over a minute ago records its use again`() {
        val key = withMediator { createApiKey(createTenant("Idle Key").id, "Idle") }
        // A historical timestamp no command can write: the key was last used two minutes ago.
        jdbi.useHandle<Exception> {
            it.createUpdate("UPDATE api_keys SET last_used_at = NOW() - INTERVAL '2 minutes' WHERE id = :id").bind("id", key.apiKey.id).execute()
        }
        val before = lastUsedAt(key)

        withMediator { RecordApiKeyUsage(key.apiKey.id).execute() }

        assertThat(lastUsedAt(key)).isAfter(before)
    }

    private fun lastUsedAt(key: ApiKeyWithSecret): OffsetDateTime? = jdbi.withHandle<OffsetDateTime?, Exception> {
        it.createQuery("SELECT last_used_at FROM api_keys WHERE id = :id").bind("id", key.apiKey.id).mapTo<OffsetDateTime>().findOne().orElse(null)
    }
}
