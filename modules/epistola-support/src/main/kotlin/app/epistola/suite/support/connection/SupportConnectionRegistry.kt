// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.privacy.SupportPrivacy
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Every support connection of the installation, live (ADR 0053): the discovered one (the
 * [SupportConnection] bean every module injects, [DefaultSupportConnection]) and every other row of
 * `support_connections`, signed in by its `auth` alone: a [TokenSupportConnection] for
 * [SupportConnectionAuth.BEARER_TOKEN], a [DefaultSupportConnection] that registers at its endpoint for
 * [SupportConnectionAuth.PRIVATE_KEY_JWT]. A connection is built once per id and kept; a connection
 * added since is picked up at the next read of the rows.
 */
@Component
class SupportConnectionRegistry(
    /** The discovered connection as every service injects it ([DefaultSupportConnection] in production). */
    private val discovered: SupportConnection,
    private val connections: SupportConnections,
    private val supportHttp: SupportHttp,
    private val properties: SupportProperties,
    private val registering: RegisteringSupportConnectionFactory? = null,
) {
    private val built = ConcurrentHashMap<UUID, SupportConnection>()

    /** Every connection, by creation, with the id of its row (what other tables refer to). */
    fun entries(): List<Pair<UUID, SupportConnection>> = connections.all().mapNotNull { record -> connectionFor(record)?.let { record.id to it } }

    /** Every connection, by creation. */
    fun all(): List<SupportConnection> = connections.all().mapNotNull { connectionFor(it) }

    /** The connection with [id]; null when there is none. */
    fun find(id: UUID): SupportConnection? = if (id == discovered.id) discovered else connections.find(id)?.let { connectionFor(it) }

    /** The connections the suite signs in to with a token: partners, typically. */
    fun tokenConnections(): List<TokenSupportConnection> = all().filterIsInstance<TokenSupportConnection>()

    /** Every connection but the discovered one, whatever it signs in with: what the check scheduler checks. */
    fun others(): List<SupportConnection> = all().filter { it.id != discovered.id }

    /** The connection with [id] when it registers through OAuth (RFC 7591, private_key_jwt) ([SupportConnectionAuth.PRIVATE_KEY_JWT]). */
    fun registering(id: UUID): DefaultSupportConnection? = find(id) as? DefaultSupportConnection

    private fun connectionFor(record: SupportConnectionRecord): SupportConnection? = when {
        // The connection epistola.support.* configures: the bean every module injects, with that configuration.
        record.auth == SupportConnectionAuth.PRIVATE_KEY_JWT && record.endpoint == connections.configuredEndpoint().toString() -> discovered

        record.auth == SupportConnectionAuth.BEARER_TOKEN -> built.compute(record.id) { _, existing ->
            existing?.takeIf { it is TokenSupportConnection && it.endpoint.toString() == record.endpoint } ?: TokenSupportConnection(
                id = record.id,
                endpoint = URI(record.endpoint.trimEnd('/')),
                store = connections.store(record.id),
                supportHttp = supportHttp,
                configuredServices = properties.connections.firstOrNull { it.baseUrl == record.endpoint.trimEnd('/') }?.services.orEmpty(),
            )
        }

        // Registers through OAuth registration at its endpoint, as Epistola support's does (ADR 0053 §1).
        record.auth == SupportConnectionAuth.PRIVATE_KEY_JWT && registering != null -> built.compute(record.id) { _, existing ->
            existing?.takeIf { it is DefaultSupportConnection && it.settings.issuer.toString() == record.endpoint.trimEnd('/') }
                ?: registering.build(record.id, URI(record.endpoint.trimEnd('/')))
        }

        // A kind this version cannot sign in to: left alone.
        else -> null
    }
}

/**
 * Puts the connections of `epistola.support.connections` into `support_connections` at startup: a new
 * endpoint becomes a connection, a known one gets its name and token from the configuration again.
 * The endpoint is the connection's identity, unique among configured connections, so nodes starting
 * together cannot create one twice. Once an administrator saved a connection (`from_configuration`
 * FALSE), the configuration leaves it alone. A connection the configuration no longer names is left as
 * it is: it may handle requests, and an administrator removes it.
 */
@Component
class ConfiguredSupportConnections(
    private val mediator: Mediator,
    private val jdbi: Jdbi,
    private val connections: SupportConnections,
    private val properties: SupportProperties,
    private val privacy: SupportPrivacy? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** First among the startup work: what runs after it finds the connections stored. */
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun apply() {
        storeEpistolaSupport()
        properties.connections.forEach { configured ->
            val id = jdbi.withHandle<UUID, Exception> { handle ->
                handle.createQuery(
                    """
                    INSERT INTO support_connections (id, name, endpoint, auth, from_configuration)
                    VALUES (:id, :name, :endpoint, :auth, TRUE)
                    ON CONFLICT (endpoint)
                    DO UPDATE SET name = EXCLUDED.name, auth = EXCLUDED.auth, updated_at = NOW()
                    WHERE support_connections.from_configuration
                    RETURNING id
                    """,
                )
                    .bind("id", UUIDv7.generate())
                    .bind("auth", if (configured.registers) SupportConnectionAuth.PRIVATE_KEY_JWT.name else SupportConnectionAuth.BEARER_TOKEN.name)
                    .bind("name", configured.name.trim())
                    .bind("endpoint", configured.baseUrl)
                    .mapTo(UUID::class.java)
                    .findOne()
                    .orElse(null)
            } ?: return@forEach
            val store = connections.store(id)
            if (!configured.registers && runCatching { store.bearerToken() }.getOrNull() != configured.token) store.saveBearerToken(configured.token)
            log.info("Support connection {} configured at {}", configured.name, configured.baseUrl)
        }
        // Where each escalates to, once all exist: another connection, named by its endpoint.
        properties.connections.forEach { configured ->
            val to = configured.escalatesTo.trim().trimEnd('/')
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE support_connections c SET escalates_to = CASE
                        WHEN :to = '' THEN NULL
                        ELSE (SELECT t.id FROM support_connections t WHERE t.endpoint = :to)
                    END
                    WHERE c.endpoint = :endpoint AND c.from_configuration
                    """,
                )
                    .bind("to", to)
                    .bind("endpoint", configured.baseUrl)
                    .execute()
            }
        }
        // The connections just stored have their privacy on this node before anything sends to them.
        privacy?.refresh()
    }

    /** With `epistola.support.enabled`, the connection `epistola.support.*` configures is stored when it is not. */
    private fun storeEpistolaSupport() {
        if (!properties.enabled) return
        MediatorContext.runWithMediator(mediator) { EnsureConfiguredSupportConnection().execute() }
    }
}

/**
 * Checks every connection but Epistola support's on one node per round, every
 * `epistola.support.check-interval`: whether it is active and which services it grants. A connection
 * that registers is checked as Epistola support's is, but registers only when an administrator asks.
 * Independent of `epistola.support.enabled`, which is the connection to Epistola support.
 */
@Component
class TokenSupportConnectionCheckScheduler(
    private val registry: SupportConnectionRegistry,
    private val properties: SupportProperties,
    private val work: SupportBackgroundWork,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val taskType: String = TASK_TYPE

    @Bean
    fun tokenSupportConnectionCheckTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = "system:$TASK_KEY",
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.checkInterval.toMillis()),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    /** Hands the checks to [SupportBackgroundWork]: the scheduler thread never waits on another side. */
    override fun handle(task: ClusterScheduledTask) {
        work.launch(TASK_KEY) { checkAll() }
    }

    /** Checks every connection but the discovered one; one that fails does not hold back the others. */
    fun checkAll() {
        registry.others().forEach { connection ->
            runCatching { connection.checkNow() }.onFailure { log.warn("Support connection {} check failed: {}", connection.id, it.javaClass.simpleName) }
        }
    }

    companion object {
        const val TASK_KEY = "support.connection.token-check"
        const val TASK_TYPE = "support.connection.token-check"
    }
}

/**
 * Builds a [DefaultSupportConnection] for a [SupportConnectionAuth.PRIVATE_KEY_JWT] row other than the
 * discovered one: the same registration, tokens and status as Epistola support's, at the row's endpoint.
 */
@Component
class RegisteringSupportConnectionFactory(
    private val properties: SupportProperties,
    private val connections: SupportConnections,
    private val client: SupportServiceClient,
    private val installations: InstallationService,
    private val installation: InstallationProperties,
    private val nodeIdentity: NodeIdentity,
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val supportHttp: SupportHttp,
) {
    fun build(id: UUID, endpoint: URI): DefaultSupportConnection = DefaultSupportConnection(
        properties,
        RegisteringConnectionSettings.forEndpoint(endpoint, properties.connections.firstOrNull { it.baseUrl == endpoint.toString() }?.services.orEmpty()),
        connections.store(id),
        client,
        installations,
        installation,
        nodeIdentity,
        buildProperties.ifAvailable,
        supportHttp,
    )
}
