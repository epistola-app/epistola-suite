// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.query
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

data class CatalogReleasePublication(
    val id: UUID,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val fingerprint: String,
    val namespace: String?,
    val archive: ByteArray?,
    val status: String,
    val idempotencyKey: UUID,
    val remotePublicationId: UUID?,
    val attempts: Int,
)

@Component
class CatalogPublicationWorker(
    private val jdbi: Jdbi,
    private val client: ExchangeClient,
    private val properties: ExchangeProperties,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun catalogPublicationScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.pollIntervalMs),
        enabled = properties.enabled,
    )

    override fun handle(task: ClusterScheduledTask) = run()

    fun run() {
        if (!properties.enabled) return
        refreshExpiringConnections()
        claim().forEach { publication ->
            if (ResolveAvailableFeatures(publication.tenantKey).query()[KnownFeatures.CATALOG_PUBLISHING] != true) {
                releaseClaim(publication.id)
                return@forEach
            }
            runCatching { process(publication) }
                .onFailure { failure -> fail(publication, failure) }
        }
    }

    private fun claim(): List<CatalogReleasePublication> = jdbi.inTransaction<List<CatalogReleasePublication>, Exception> { handle ->
        val rows = handle.createQuery(
            """
            SELECT * FROM catalog_release_publications
            WHERE status IN ('WAITING_SETUP', 'READY', 'SUBMITTED', 'RETRY')
              AND next_attempt_at <= NOW()
              AND (claimed_at IS NULL OR claimed_at < NOW() - INTERVAL '5 minutes')
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT 10
            """,
        ).map(::mapPublication).list()
        rows.forEach { row ->
            handle.createUpdate("UPDATE catalog_release_publications SET claimed_at = NOW() WHERE id = :id")
                .bind("id", row.id).execute()
        }
        rows
    }

    private fun process(publication: CatalogReleasePublication) {
        val prepared = if (publication.namespace == null) resolveSetup(publication) ?: return else publication
        val token = accessToken(prepared.tenantKey) ?: run {
            releaseClaim(prepared.id)
            return
        }
        val response = if (prepared.remotePublicationId == null) {
            client.submit(
                connectionBaseUrl(prepared.tenantKey),
                token,
                requireNotNull(prepared.namespace),
                requireNotNull(prepared.archive),
                prepared.idempotencyKey,
            )
        } else {
            client.publication(connectionBaseUrl(prepared.tenantKey), token, prepared.remotePublicationId)
        }
        applyRemoteState(prepared.id, response)
    }

    private fun resolveSetup(publication: CatalogReleasePublication): CatalogReleasePublication? = jdbi.inTransaction<CatalogReleasePublication?, Exception> { handle ->
        val selection = handle.createQuery(
            """
                SELECT c.exchange_namespace_preference, x.namespaces, x.default_namespace
                FROM catalogs c JOIN exchange_tenant_connections x ON x.tenant_key = c.tenant_key
                WHERE c.tenant_key = :tenantKey AND c.id = :catalogKey AND x.status = 'ACTIVE'
                """,
        ).bind("tenantKey", publication.tenantKey).bind("catalogKey", publication.catalogKey).map { rs, _ ->
            val allowed = (rs.getArray("namespaces").array as Array<*>).map(Any?::toString).toSet()
            (
                rs.getString("exchange_namespace_preference")?.takeIf(allowed::contains)
                    ?: rs.getString("default_namespace")
                )?.takeIf(allowed::contains)
        }.findOne().orElse(null)
        if (selection == null) {
            releaseClaim(publication.id, handle)
            return@inTransaction null
        }
        handle.createUpdate(
            """
                INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace)
                VALUES (:tenantKey, :catalogKey, :namespace)
                ON CONFLICT (tenant_key, catalog_key) DO NOTHING
                """,
        ).bind("tenantKey", publication.tenantKey).bind("catalogKey", publication.catalogKey)
            .bind("namespace", selection).execute()
        handle.createUpdate(
            "UPDATE catalog_release_publications SET namespace = :namespace, status = 'READY' WHERE id = :id",
        ).bind("namespace", selection).bind("id", publication.id).execute()
        publication.copy(namespace = selection, status = "READY")
    }

    private fun accessToken(tenantKey: TenantKey): String? = jdbi.inTransaction<String?, Exception> { handle ->
        val connection = handle.createQuery(
            "SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenantKey FOR UPDATE",
        ).bind("tenantKey", tenantKey).mapTo(ExchangeTenantConnection::class.java).findOne().orElse(null)
            ?: return@inTransaction null
        if (connection.status != ExchangeConnectionStatus.ACTIVE) return@inTransaction null
        val now = EpistolaClock.offsetDateTime()
        if (connection.accessToken != null && connection.accessTokenExpiresAt?.isAfter(now.plusSeconds(30)) == true) {
            return@inTransaction connection.accessToken.value
        }
        val refresh = connection.refreshToken ?: return@inTransaction null
        val applicationId = connection.oauthApplicationId ?: return@inTransaction null
        val clientSecret = connection.clientSecret ?: return@inTransaction null
        val endpoints = ExchangeEndpoints(
            connection.issuer,
            connection.baseUrl,
            "${connection.issuer}/oauth/authorization-requests",
            "${connection.issuer}/oauth/token",
        )
        val token = try {
            client.refresh(endpoints, refresh.value, applicationId, clientSecret.value)
        } catch (failure: HttpClientErrorException.BadRequest) {
            handle.createUpdate(
                "UPDATE exchange_tenant_connections SET status = 'REAUTHORIZATION_REQUIRED', last_error = :error WHERE tenant_key = :tenantKey",
            ).bind("error", "Refresh token was rejected").bind("tenantKey", tenantKey).execute()
            return@inTransaction null
        }
        handle.createUpdate(
            """
            UPDATE exchange_tenant_connections SET access_token = :accessToken,
                access_token_expires_at = :accessExpiresAt, refresh_token = :refreshToken,
                refresh_token_expires_at = :refreshExpiresAt, updated_at = NOW()
            WHERE tenant_key = :tenantKey
            """,
        ).bind("accessToken", Secret(token.accessToken)).bind("accessExpiresAt", now.plus(token.accessTokenExpiresIn))
            .bind("refreshToken", Secret(token.refreshToken)).bind("refreshExpiresAt", now.plus(token.refreshTokenExpiresIn))
            .bind("tenantKey", tenantKey).execute()
        token.accessToken
    }

    private fun refreshExpiringConnections() {
        val tenants = jdbi.withHandle<List<TenantKey>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT tenant_key FROM exchange_tenant_connections
                WHERE status = 'ACTIVE' AND refresh_token IS NOT NULL
                  AND (access_token_expires_at IS NULL OR access_token_expires_at <= NOW() + INTERVAL '5 minutes')
                """,
            ).mapTo(String::class.java).list().map(TenantKey::of)
        }
        tenants.forEach { tenantKey ->
            runCatching { accessToken(tenantKey) }
                .onFailure { logger.warn("Exchange credential refresh failed for tenant {}: {}", tenantKey, it.message) }
        }
    }

    private fun connectionBaseUrl(tenantKey: TenantKey): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT base_url FROM exchange_tenant_connections WHERE tenant_key = :tenantKey")
            .bind("tenantKey", tenantKey).mapTo(String::class.java).one()
    }

    private fun applyRemoteState(id: UUID, remote: ExchangePublicationResponse) {
        val local = when (remote.state) {
            "ACCEPTED" -> "ACCEPTED"
            "REJECTED" -> "REJECTED"
            "FAILED" -> "FAILED"
            else -> "SUBMITTED"
        }
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_release_publications SET status = :status,
                    remote_publication_id = :remoteId,
                    remote_status_url = :statusUrl,
                    archive = CASE WHEN :terminal THEN NULL ELSE archive END,
                    last_error = :error, claimed_at = NULL,
                    next_attempt_at = NOW() + INTERVAL '5 seconds', updated_at = NOW()
                WHERE id = :id
                """,
            ).bind("status", local).bind("remoteId", remote.id)
                .bind("statusUrl", "/api/v1/publication-submissions/${remote.id}")
                .bind("terminal", local in setOf("ACCEPTED", "REJECTED"))
                .bind("error", listOfNotNull(remote.errorCode, remote.errorDetail).joinToString(": ").ifBlank { null })
                .bind("id", id).execute()
        }
    }

    private fun fail(publication: CatalogReleasePublication, failure: Throwable) {
        val forbidden = failure is HttpClientErrorException.Forbidden
        val unauthorized = failure is HttpClientErrorException.Unauthorized
        if (forbidden || unauthorized) {
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    "UPDATE exchange_tenant_connections SET status = :status, last_error = :error WHERE tenant_key = :tenantKey",
                ).bind("status", if (unauthorized) "REAUTHORIZATION_REQUIRED" else "BLOCKED")
                    .bind("error", failure.message).bind("tenantKey", publication.tenantKey).execute()
            }
        }
        val delaySeconds = minOf(3600L, 5L * (1L shl minOf(publication.attempts, 9)))
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_release_publications SET status = 'RETRY', attempts = attempts + 1,
                    last_error = :error, claimed_at = NULL,
                    next_attempt_at = NOW() + (:delay * INTERVAL '1 second'), updated_at = NOW()
                WHERE id = :id
                """,
            ).bind("error", failure.message ?: failure.javaClass.simpleName).bind("delay", delaySeconds)
                .bind("id", publication.id).execute()
        }
        logger.warn("Exchange publication {} failed: {}", publication.id, failure.message)
    }

    private fun releaseClaim(id: UUID) = jdbi.useHandle<Exception> { releaseClaim(id, it) }

    private fun releaseClaim(id: UUID, handle: Handle) {
        handle.createUpdate("UPDATE catalog_release_publications SET claimed_at = NULL WHERE id = :id")
            .bind("id", id).execute()
    }

    private fun mapPublication(rs: java.sql.ResultSet, _context: org.jdbi.v3.core.statement.StatementContext) = CatalogReleasePublication(
        rs.getObject("id", UUID::class.java),
        TenantKey.of(rs.getString("tenant_key")),
        CatalogKey.of(rs.getString("catalog_key")),
        rs.getString("version"),
        rs.getString("fingerprint"),
        rs.getString("namespace"),
        rs.getBytes("archive"),
        rs.getString("status"),
        rs.getObject("idempotency_key", UUID::class.java),
        rs.getObject("remote_publication_id", UUID::class.java),
        rs.getInt("attempts"),
    )

    companion object {
        const val TASK_KEY = "core.exchange-catalog-publication"
        const val ROUTING_KEY = "system:core.exchange-catalog-publication"
        const val TASK_TYPE = "core.exchange-catalog-publication"
    }
}
