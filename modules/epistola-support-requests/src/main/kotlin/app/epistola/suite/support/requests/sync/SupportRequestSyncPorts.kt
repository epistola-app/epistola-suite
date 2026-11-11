// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.TokenSupportConnection
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.requests.adapters.RestSupportRequestSyncAdapter
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
    /** The port of the connection `epistola.support.*` configures ([configured]): replaced by a test's stand-in. */
    private val discovered: SupportRequestSyncPort,
    private val configured: SupportConnection,
    private val registry: SupportConnectionRegistry,
    private val objectMapper: ObjectMapper,
    private val references: TenantReferences,
    private val jdbi: Jdbi,
    private val privacy: SupportPrivacy,
) {
    /**
     * [connectionId]'s port; null for a connection that does not exist or that this version cannot send to.
     * Built per call, not kept: the adapter holds nothing but the connection, so a removed connection
     * leaves nothing behind.
     */
    fun port(connectionId: UUID): SupportRequestSyncPort? {
        val connection = registry.find(connectionId) ?: return null
        if (connection === configured) return discovered
        return RestSupportRequestSyncAdapter(connection, objectMapper, references, jdbi, privacy)
    }

    /** Every connection's port, by creation, with its connection id. */
    fun all(): List<Pair<UUID, SupportRequestSyncPort>> = registry.entries().mapNotNull { (id, _) -> port(id)?.let { id to it } }
}
