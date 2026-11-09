// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys.queries

import app.epistola.suite.apikeys.API_KEY_COLUMNS
import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.toApiKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Looks up an API key by its SHA-256 hash. Returns null if no key matches.
 *
 * `SystemInternal` because it is invoked by [ApiKeyAuthenticationFilter] before
 * any user has been authenticated — there is no principal to authorize against.
 */
data class LookupApiKeyByHash(
    val keyHash: String,
) : Query<ApiKey?>,
    SystemInternal

@Component
class LookupApiKeyByHashHandler(
    private val jdbi: Jdbi,
) : QueryHandler<LookupApiKeyByHash, ApiKey?> {
    override fun handle(query: LookupApiKeyByHash): ApiKey? = jdbi.withHandle<ApiKey?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT $API_KEY_COLUMNS
            FROM api_keys k
            LEFT JOIN applications a ON a.id = k.application_id
            WHERE k.key_hash = :keyHash
            """,
        )
            .bind("keyHash", query.keyHash)
            .map { rs, _ -> rs.toApiKey(withDisplayName = false) }
            .findOne()
            .orElse(null)
    }
}
