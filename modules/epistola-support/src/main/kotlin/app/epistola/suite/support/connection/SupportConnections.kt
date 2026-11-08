// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.crypto.CredentialCipher
import org.jdbi.v3.core.Jdbi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Where a connection's endpoint comes from (ADR 0053 §1a). */
enum class SupportConnectionSource {
    /** The product domain's discovery document (ADR 0030), or `epistola.support.endpoint`. */
    DISCOVERED,

    /** Set by an administrator or the configuration. */
    CONFIGURED,
}

/** How the suite signs in to a connection's endpoint. */
enum class SupportConnectionAuth {
    /** Dynamic registration with the installation's own key, then `private_key_jwt` (ADR 0050). */
    PRIVATE_KEY_JWT,

    /** A token the other side issued, set in configuration. */
    BEARER_TOKEN,
}

/**
 * A support connection as configured (ADR 0053): who it is and how the suite reaches it. What the
 * connection holds at runtime (registration, status, services) is in its [SupportConnectionStore].
 *
 * @property endpoint null for the [SupportConnectionSource.DISCOVERED] connection: discovery names it.
 * @property escalatesTo the connection a request handled here is passed on to, if any.
 */
data class SupportConnectionRecord(
    val id: UUID,
    val name: String,
    val source: SupportConnectionSource,
    val endpoint: String?,
    val auth: SupportConnectionAuth,
    val escalatesTo: UUID?,
    val createdAt: Instant,
)

/**
 * The installation's support connections (ADR 0053). Nothing tells Epistola apart from any other
 * party: the connection the product domain's discovery document names is the one with
 * [SupportConnectionSource.DISCOVERED], created by the migration with [DISCOVERED_ID].
 */
@Component
class SupportConnections(
    private val jdbi: Jdbi,
    private val cipher: CredentialCipher,
    private val objectMapper: ObjectMapper,
) {
    /** Every connection, the discovered one first, then by creation. */
    fun all(): List<SupportConnectionRecord> = select("ORDER BY source = 'DISCOVERED' DESC, created_at, id", emptyMap())

    /** The connection with [id]; null when there is none. */
    fun find(id: UUID): SupportConnectionRecord? = select("WHERE id = :id", mapOf("id" to id)).firstOrNull()

    private fun select(rest: String, binds: Map<String, Any>): List<SupportConnectionRecord> = jdbi.withHandle<List<SupportConnectionRecord>, Exception> { handle ->
        handle.createQuery("SELECT id, name, source, endpoint, auth, escalates_to, created_at FROM support_connections $rest")
            .bindMap(binds)
            .map { rs, _ ->
                SupportConnectionRecord(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    source = SupportConnectionSource.valueOf(rs.getString("source")),
                    endpoint = rs.getString("endpoint"),
                    auth = SupportConnectionAuth.valueOf(rs.getString("auth")),
                    escalatesTo = rs.getObject("escalates_to", UUID::class.java),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
    }

    /** The store of connection [id]: its registration, status, services and the rest of its row. */
    fun store(id: UUID): SupportConnectionStore = SupportConnectionStore(jdbi, cipher, objectMapper, id)

    companion object {
        /** [DISCOVERED_ID] as text, for SQL constants. */
        const val DISCOVERED_ID_TEXT = "01900000-0000-7000-8000-000000000001"

        /** The id of the connection the product domain's discovery document names. */
        val DISCOVERED_ID: UUID = UUID.fromString(DISCOVERED_ID_TEXT)
    }
}

/** The discovered connection's store: the one [DefaultSupportConnection] works with. */
@Configuration(proxyBeanMethods = false)
class SupportConnectionStoreConfiguration {
    @Bean
    fun supportConnectionStore(connections: SupportConnections): SupportConnectionStore = connections.store(SupportConnections.DISCOVERED_ID)
}
