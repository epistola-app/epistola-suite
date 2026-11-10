// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.productdomain.ProductDomainProperties
import app.epistola.suite.support.SupportProperties
import org.jdbi.v3.core.Jdbi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** How the suite signs in to a connection's endpoint. */
enum class SupportConnectionAuth {
    /** OAuth dynamic client registration (RFC 7591), then `private_key_jwt` (RFC 7523) (ADR 0050). */
    PRIVATE_KEY_JWT,

    /** A token the other side issued, set in configuration. */
    BEARER_TOKEN,
}

/**
 * A support connection as configured (ADR 0053): who it is and how the suite reaches it. What the
 * connection holds at runtime (registration, status, services) is in its [SupportConnectionStore].
 *
 * @property endpoint the other side's base URL or issuer, without a trailing slash: the connection's identity.
 * @property escalatesTo the connection a request handled here is passed on to, if any.
 */
data class SupportConnectionRecord(
    val id: UUID,
    val name: String,
    val endpoint: String,
    val auth: SupportConnectionAuth,
    val escalatesTo: UUID?,
    val createdAt: Instant,
)

/**
 * The installation's support connections (ADR 0053). Nothing tells Epistola apart from any other
 * party: every connection has an endpoint, a way to sign in and the services it grants. The one
 * `epistola.support.*` configures ([configuredEndpoint]) is stored at startup while
 * `epistola.support.enabled` is on ([ConfiguredSupportConnections]), so a fresh installation starts
 * with it, and it can be removed like any other.
 */
@Component
class SupportConnections(
    private val jdbi: Jdbi,
    private val cipher: CredentialCipher,
    private val objectMapper: ObjectMapper,
    private val properties: SupportProperties,
    private val domain: ProductDomainProperties,
) {
    /** Every connection, by creation. */
    fun all(): List<SupportConnectionRecord> = select("ORDER BY created_at, id", emptyMap())

    /** The connection with [id]; null when there is none. */
    fun find(id: UUID): SupportConnectionRecord? = select("WHERE id = :id", mapOf("id" to id)).firstOrNull()

    /** The connection at [endpoint]; null when there is none. */
    fun findByEndpoint(endpoint: String): SupportConnectionRecord? = select("WHERE endpoint = :endpoint", mapOf("endpoint" to endpoint.trimEnd('/'))).firstOrNull()

    /**
     * The endpoint of the connection `epistola.support.*` configures: `epistola.support.endpoint`, by
     * default the support service on the product domain (`https://support.{product-domain}`).
     */
    fun configuredEndpoint(): URI = properties.configuredIssuer ?: URI("https://support.${domain.host}")

    /** The id of the connection `epistola.support.*` configures; null while it is not stored. */
    fun configuredId(): UUID? = findByEndpoint(configuredEndpoint().toString())?.id

    private fun select(rest: String, binds: Map<String, Any>): List<SupportConnectionRecord> = jdbi.withHandle<List<SupportConnectionRecord>, Exception> { handle ->
        handle.createQuery("SELECT id, name, endpoint, auth, escalates_to, created_at FROM support_connections $rest")
            .bindMap(binds)
            .map { rs, _ ->
                SupportConnectionRecord(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    endpoint = rs.getString("endpoint"),
                    auth = SupportConnectionAuth.valueOf(rs.getString("auth")),
                    escalatesTo = rs.getObject("escalates_to", UUID::class.java),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
    }

    /** The store of connection [id]: its registration, status, services and the rest of its row. */
    fun store(id: UUID): SupportConnectionStore = SupportConnectionStore(jdbi, cipher, objectMapper, id)

    /**
     * The store of the connection `epistola.support.*` configures, whichever row that is now: its id is
     * looked up by [configuredEndpoint] and kept for a moment, so a connection removed and stored again
     * (at the next start) is found again.
     */
    fun configuredStore(): SupportConnectionStore = SupportConnectionStore(jdbi, cipher, objectMapper, ConfiguredIdLookup(::configuredId))

    /**
     * [configuredId], an id found remembered for [TTL]: the store reads its id on every call. Not
     * finding one is never remembered, so the connection stored at startup is found at once.
     */
    private class ConfiguredIdLookup(private val lookup: () -> UUID?) : () -> UUID? {
        @Volatile private var cached: Pair<UUID, Long>? = null

        override fun invoke(): UUID? {
            val now = System.nanoTime()
            cached?.takeIf { now - it.second < TTL.toNanos() }?.let { return it.first }
            return runCatching(lookup).getOrNull().also { found -> cached = found?.let { it to now } }
        }
    }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(5)
    }
}

/** The configured connection's store: the one the [DefaultSupportConnection] bean works with. */
@Configuration(proxyBeanMethods = false)
class SupportConnectionStoreConfiguration {
    @Bean
    fun supportConnectionStore(connections: SupportConnections): SupportConnectionStore = connections.configuredStore()
}
