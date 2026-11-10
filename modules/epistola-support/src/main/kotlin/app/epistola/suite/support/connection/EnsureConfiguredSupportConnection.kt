// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Stores the connection `epistola.support.*` configures at its endpoint
 * ([SupportConnections.configuredEndpoint]) when it is not stored, and returns its id: what a start
 * with `epistola.support.enabled` does ([ConfiguredSupportConnections]), at the first start and again
 * after an administrator removed it. One that exists is left as it is. Safe on nodes starting together:
 * the endpoint is unique.
 */
class EnsureConfiguredSupportConnection :
    Command<UUID>,
    // The startup applies the deployment's configuration; no person asks for it.
    SystemInternal

@Component
class EnsureConfiguredSupportConnectionHandler(
    private val jdbi: Jdbi,
    private val connections: SupportConnections,
) : CommandHandler<EnsureConfiguredSupportConnection, UUID> {
    override fun handle(command: EnsureConfiguredSupportConnection): UUID {
        val endpoint = connections.configuredEndpoint().toString()
        return jdbi.withHandle<UUID, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO support_connections (id, name, endpoint, auth, from_configuration)
                VALUES (:id, :name, :endpoint, 'PRIVATE_KEY_JWT', TRUE)
                ON CONFLICT (endpoint) DO NOTHING
                """,
            )
                .bind("id", UUIDv7.generate())
                .bind("name", NAME)
                .bind("endpoint", endpoint)
                .execute()
            handle.createQuery("SELECT id FROM support_connections WHERE endpoint = :endpoint").bind("endpoint", endpoint).mapTo(UUID::class.java).one()
        }
    }

    companion object {
        /** What the connection is called when it is stored. */
        const val NAME = "Epistola support"
    }
}
