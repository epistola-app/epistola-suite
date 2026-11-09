// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.connection.TokenSupportConnection
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.requests.adapters.RestSupportRequestSyncAdapter
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The [SupportRequestSyncPort] of every support connection (ADR 0053 step 3): Epistola support's is
 * the port bean (the REST adapter, or a stand-in), another connection's a REST adapter over that
 * connection, built when used. The protocol is the same for every connection; which endpoint and
 * which privacy is the connection's.
 */
@Component
class SupportRequestSyncPorts(
    private val discovered: SupportRequestSyncPort,
    private val registry: SupportConnectionRegistry,
    private val objectMapper: ObjectMapper,
    private val references: TenantReferences,
    private val jdbi: Jdbi,
) {
    /**
     * [connectionId]'s port; null for a connection that does not exist or that this version cannot send to.
     * Built per call, not kept: the adapter holds nothing but the connection, so a removed connection
     * leaves nothing behind.
     */
    fun port(connectionId: UUID): SupportRequestSyncPort? {
        if (connectionId == REQUESTS_CONNECTION) return discovered
        val connection = registry.find(connectionId) as? TokenSupportConnection ?: return null
        return RestSupportRequestSyncAdapter(connection, objectMapper, references, jdbi)
    }

    /** The installation selected support requests for connection [connectionId] (another than Epistola support's). */
    fun selectsRequests(connectionId: UUID): Boolean = registry.find(connectionId)?.isSelected(SupportScope.REQUESTS) == true

    /** Every connection's port, Epistola support's first, with its connection id. */
    fun all(): List<Pair<UUID, SupportRequestSyncPort>> = listOf(REQUESTS_CONNECTION to discovered) +
        registry.tokenConnections().mapNotNull { c -> port(c.id)?.let { c.id to it } }
}
