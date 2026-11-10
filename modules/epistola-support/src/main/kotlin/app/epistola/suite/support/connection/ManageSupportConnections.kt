// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.privacy.StoredSupportPrivacy
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TelemetryDetail
import app.epistola.suite.support.privacy.TenantIdentity
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

/**
 * What a server publishes about connecting to it (ADR 0053 §1a), from the `support` object of its
 * `/.well-known/epistola.json`: everything to set a connection up but the token, which its operator
 * hands out.
 */
data class SupportConnectionPreset(
    val name: String,
    val endpoint: String,
    val services: List<String>,
    val tenantIdentity: TenantIdentity?,
    val telemetry: TelemetryDetail?,
)

/** A preset could not be read; [reason] says why, in words for the platform page. */
class SupportConnectionPresetUnavailableException(val reason: String) : RuntimeException(reason)

/** A connection with that endpoint exists already. */
class SupportConnectionExistsException(val endpoint: String) : RuntimeException("A support connection to $endpoint exists already")

/**
 * Reads the preset [domain] publishes (`https://{domain}/.well-known/epistola.json`), to fill in the
 * Add connection form. Network I/O, for an installation administrator; with
 * `epistola.support.allow-http` a full `http://` address is taken too, for a local server.
 */
data class DiscoverSupportConnectionPreset(
    val domain: String,
) : Query<SupportConnectionPreset>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class DiscoverSupportConnectionPresetHandler(
    private val supportHttp: SupportHttp,
    private val properties: SupportProperties,
) : QueryHandler<DiscoverSupportConnectionPreset, SupportConnectionPreset> {
    override fun handle(query: DiscoverSupportConnectionPreset): SupportConnectionPreset {
        val base = baseOf(query.domain.trim())
        val document = try {
            RestClient.builder().requestFactory(supportHttp.requestFactory()).build()
                .get().uri("$base/.well-known/epistola.json").accept(MediaType.APPLICATION_JSON)
                .retrieve().body(JsonNode::class.java)
        } catch (e: Exception) {
            throw SupportConnectionPresetUnavailableException("$base did not answer with a discovery document (${e.javaClass.simpleName}).")
        } ?: throw SupportConnectionPresetUnavailableException("$base answered with an empty discovery document.")
        val support = document.path("support").takeIf { it.isObject }
            ?: throw SupportConnectionPresetUnavailableException("$base publishes no support connection in its discovery document.")
        val endpoint = support.path("endpoint").stringValue("").trim()
        if (endpoint.isEmpty()) throw SupportConnectionPresetUnavailableException("$base names no endpoint for its support connection.")
        return SupportConnectionPreset(
            name = support.path("name").stringValue("").trim().ifEmpty { URI(base).host },
            endpoint = endpoint,
            services = support.path("services").takeIf { it.isArray }?.mapNotNull { s -> s.takeIf { it.isString }?.stringValue() }.orEmpty(),
            tenantIdentity = support.path("tenantIdentity").stringValue(null)?.let(TenantIdentity::parse),
            telemetry = support.path("telemetry").stringValue(null)?.let(TelemetryDetail::parse),
        )
    }

    /** A bare domain as `https://`; a full address only `https:`, or `http:` with `allow-http`. */
    private fun baseOf(input: String): String {
        if (!input.contains("://")) {
            if (!DOMAIN.matches(input)) throw SupportConnectionPresetUnavailableException("'$input' is not a domain name.")
            return "https://$input"
        }
        val url = runCatching { URI(input) }.getOrNull()?.takeIf { it.host != null }
            ?: throw SupportConnectionPresetUnavailableException("'$input' is not an address.")
        if (url.scheme != "https" && !(url.scheme == "http" && properties.allowHttp)) throw SupportConnectionPresetUnavailableException("Only https addresses are looked up.")
        return "${url.scheme}://${url.authority}"
    }

    private companion object {
        val DOMAIN = Regex("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$")
    }
}

/**
 * Adds a support connection as an installation administrator (ADR 0053 §1a), from a preset or by hand:
 * one the suite signs in to with a token the other side issued ([SupportConnectionAuth.BEARER_TOKEN]),
 * or one that registers with the installation's own key, as Epistola support's does
 * ([SupportConnectionAuth.PRIVATE_KEY_JWT]; no token, registered when an administrator asks). The
 * configuration leaves it alone.
 */
data class AddSupportConnection(
    val name: String,
    val endpoint: String,
    val token: String,
    val services: List<String>,
    val tenantIdentity: TenantIdentity? = null,
    val telemetry: TelemetryDetail? = null,
    val auth: SupportConnectionAuth = SupportConnectionAuth.BEARER_TOKEN,
) : Command<UUID>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR

    init {
        validate("name", name.isNotBlank() && name.length <= MAX_NAME) { "Give the connection a name of at most $MAX_NAME characters" }
        validate("token", auth != SupportConnectionAuth.BEARER_TOKEN || token.isNotBlank()) { "The token the other side issued is required" }
        validate("endpoint", runCatching { URI(endpoint.trim()) }.getOrNull()?.let { it.isAbsolute && it.host != null && it.scheme in setOf("https", "http") } == true) {
            "The endpoint must be an absolute https address"
        }
    }

    /** [endpoint] without surrounding blanks or a trailing slash: the connection's identity. */
    val baseUrl: String get() = endpoint.trim().trimEnd('/')

    override fun toString(): String = "AddSupportConnection(name=$name, endpoint=$endpoint, services=$services, auth=$auth)"

    companion object {
        const val MAX_NAME = 100
    }
}

@Component
class AddSupportConnectionHandler(
    private val jdbi: Jdbi,
    private val connections: SupportConnections,
    private val properties: SupportProperties,
    private val privacy: SupportPrivacy,
) : CommandHandler<AddSupportConnection, UUID> {
    override fun handle(command: AddSupportConnection): UUID {
        validate("endpoint", properties.allowHttp || command.baseUrl.startsWith("https://")) { "The endpoint must be an https address" }
        val id = jdbi.withHandle<UUID?, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO support_connections (id, name, endpoint, auth, from_configuration)
                VALUES (:id, :name, :endpoint, :auth, FALSE)
                ON CONFLICT (endpoint) DO NOTHING
                RETURNING id
                """,
            )
                .bind("id", UUIDv7.generate())
                .bind("auth", command.auth.name)
                .bind("name", command.name.trim())
                .bind("endpoint", command.baseUrl)
                .mapTo(UUID::class.java)
                .findOne()
                .orElse(null)
        } ?: throw SupportConnectionExistsException(command.baseUrl)
        val store = connections.store(id)
        if (command.auth == SupportConnectionAuth.BEARER_TOKEN) store.saveBearerToken(command.token.trim())
        store.saveServices(StoredSupportServices(SupportScope.normalize(command.services), EpistolaClock.instant(), SecurityContext.current().displayName))
        if (command.tenantIdentity != null || command.telemetry != null) {
            val installation = privacy.current()
            privacy.saveFor(
                id,
                StoredSupportPrivacy(
                    (command.tenantIdentity ?: installation.tenantIdentity).name,
                    (command.telemetry ?: installation.telemetry).name,
                    SecurityContext.current().displayName,
                    EpistolaClock.offsetDateTime(),
                ),
            )
        }
        return id
    }
}

/**
 * Saves a support connection's name and the services used over it, and replaces its token when
 * [token] is given. From then on the configuration leaves it alone.
 */
data class UpdateSupportConnection(
    val connectionId: UUID,
    val name: String,
    val services: List<String>,
    val token: String? = null,
    /** The connection it escalates to (ADR 0053 §4), when [changeEscalation]; null: none. */
    val escalatesTo: UUID? = null,
    val changeEscalation: Boolean = false,
) : Command<Unit>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR

    init {
        validate("name", name.isNotBlank() && name.length <= AddSupportConnection.MAX_NAME) { "Give the connection a name of at most ${AddSupportConnection.MAX_NAME} characters" }
    }

    override fun toString(): String = "UpdateSupportConnection(connectionId=$connectionId, name=$name, services=$services)"
}

@Component
class UpdateSupportConnectionHandler(
    private val jdbi: Jdbi,
    private val connections: SupportConnections,
) : CommandHandler<UpdateSupportConnection, Unit> {
    override fun handle(command: UpdateSupportConnection) {
        val updated = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                "UPDATE support_connections SET name = :name, from_configuration = FALSE, version = version + 1, updated_at = NOW() WHERE id = :id",
            ).bind("id", command.connectionId).bind("name", command.name.trim()).execute()
        }
        if (updated == 0) throw app.epistola.suite.support.privacy.SupportConnectionNotFoundException(command.connectionId)
        val store = connections.store(command.connectionId)
        store.saveServices(StoredSupportServices(SupportScope.normalize(command.services), EpistolaClock.instant(), SecurityContext.current().displayName))
        command.token?.trim()?.takeIf { it.isNotEmpty() }?.let { store.saveBearerToken(it) }
        if (command.changeEscalation) {
            validate("escalatesTo", command.escalatesTo != command.connectionId) { "A connection cannot escalate to itself" }
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate("UPDATE support_connections SET escalates_to = (SELECT id FROM support_connections WHERE id = :to) WHERE id = :id")
                    .bind("id", command.connectionId).bind("to", command.escalatesTo).execute()
            }
        }
    }
}

/**
 * Removes a support connection, any one, Epistola support's too (ADR 0053 §1): requests it handled
 * return to the installation's inbox, and what it was still to get, its sharing settings and its usage
 * bookkeeping go; replies it sent keep its name. What it has keeps. One registered with the
 * installation's own key is disconnected first ([SupportConnectionStillRegisteredException]), so its
 * registration ends at the other side too. The one `epistola.support.*` configures is stored again at
 * the next start while `epistola.support.enabled` is on.
 */
data class RemoveSupportConnection(
    val connectionId: UUID,
) : Command<Boolean>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class RemoveSupportConnectionHandler(
    private val jdbi: Jdbi,
    private val registry: SupportConnectionRegistry,
) : CommandHandler<RemoveSupportConnection, Boolean> {
    /** @throws SupportConnectionStillRegisteredException while it is registered with the installation's own key. */
    override fun handle(command: RemoveSupportConnection): Boolean {
        // Removing would leave the registration, and what the other side holds, at the other side: disconnect first.
        registry.registering(command.connectionId)?.takeIf { stillRegistered(it.status().state) }?.let {
            throw SupportConnectionStillRegisteredException(command.connectionId)
        }
        return jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createUpdate("DELETE FROM support_connections WHERE id = :id").bind("id", command.connectionId).execute() > 0
        }
    }
}

/** Registered with the installation's own key, so the other side holds a registration a disconnect ends. */
internal fun stillRegistered(state: SupportConnectionState): Boolean = state is SupportConnectionState.Pending || state is SupportConnectionState.Active || state is SupportConnectionState.Refused

/** A connection still registered with the installation's own key is disconnected before it is removed. */
class SupportConnectionStillRegisteredException(val connectionId: UUID) : RuntimeException("Support connection $connectionId is still registered: disconnect it before removing it")
