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
import app.epistola.suite.support.SupportProperties
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Every support connection of the installation, live (ADR 0053): the discovered one (the
 * [SupportConnection] bean every module injects, [DefaultSupportConnection]) and every
 * [SupportConnectionAuth.BEARER_TOKEN] connection in `support_connections`. A connection is built once
 * per id and kept; a connection added since is picked up at the next read of the rows.
 */
@Component
class SupportConnectionRegistry(
    /** The discovered connection as every service injects it ([DefaultSupportConnection] in production). */
    private val discovered: SupportConnection,
    private val connections: SupportConnections,
    private val supportHttp: SupportHttp,
    private val properties: SupportProperties,
) {
    private val built = ConcurrentHashMap<UUID, TokenSupportConnection>()

    /** Every connection, the discovered one first, then by creation. */
    fun all(): List<SupportConnection> = connections.all().mapNotNull { connectionFor(it) }

    /** The connection with [id]; null when there is none. */
    fun find(id: UUID): SupportConnection? = if (id == discovered.id) discovered else connections.find(id)?.let { connectionFor(it) }

    /** The connections the suite signs in to with a token: partners, typically. */
    fun tokenConnections(): List<TokenSupportConnection> = all().filterIsInstance<TokenSupportConnection>()

    private fun connectionFor(record: SupportConnectionRecord): SupportConnection? = when {
        record.source == SupportConnectionSource.DISCOVERED -> discovered

        record.auth == SupportConnectionAuth.BEARER_TOKEN && record.endpoint != null -> built.compute(record.id) { _, existing ->
            existing?.takeIf { it.endpoint.toString() == record.endpoint } ?: TokenSupportConnection(
                id = record.id,
                endpoint = URI(record.endpoint.trimEnd('/')),
                store = connections.store(record.id),
                supportHttp = supportHttp,
                configuredServices = properties.connections.firstOrNull { it.baseUrl == record.endpoint.trimEnd('/') }?.services.orEmpty(),
            )
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
    private val jdbi: Jdbi,
    private val connections: SupportConnections,
    private val properties: SupportProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun apply() {
        properties.connections.forEach { configured ->
            val id = jdbi.withHandle<UUID, Exception> { handle ->
                handle.createQuery(
                    """
                    INSERT INTO support_connections (id, name, source, endpoint, auth, from_configuration)
                    VALUES (:id, :name, 'CONFIGURED', :endpoint, 'BEARER_TOKEN', TRUE)
                    ON CONFLICT (endpoint) WHERE source = 'CONFIGURED'
                    DO UPDATE SET name = EXCLUDED.name, auth = EXCLUDED.auth, updated_at = NOW()
                    WHERE support_connections.from_configuration
                    RETURNING id
                    """,
                )
                    .bind("id", UUIDv7.generate())
                    .bind("name", configured.name.trim())
                    .bind("endpoint", configured.baseUrl)
                    .mapTo(UUID::class.java)
                    .findOne()
                    .orElse(null)
            } ?: return@forEach
            val store = connections.store(id)
            if (runCatching { store.bearerToken() }.getOrNull() != configured.token) store.saveBearerToken(configured.token)
            log.info("Support connection {} configured at {}", configured.name, configured.baseUrl)
        }
    }
}

/**
 * Checks every token connection on one node per round, every `epistola.support.check-interval`:
 * whether it still accepts the token and which services it grants. Independent of
 * `epistola.support.enabled`, which is the connection to Epistola support.
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

    /** Checks every token connection; one that fails does not hold back the others. */
    fun checkAll() {
        registry.tokenConnections().forEach { connection ->
            runCatching { connection.checkNow() }.onFailure { log.warn("Support connection {} check failed: {}", connection.id, it.javaClass.simpleName) }
        }
    }

    companion object {
        const val TASK_KEY = "support.connection.token-check"
        const val TASK_TYPE = "support.connection.token-check"
    }
}
