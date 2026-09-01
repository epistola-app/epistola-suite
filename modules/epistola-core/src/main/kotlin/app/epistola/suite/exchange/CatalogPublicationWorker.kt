// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import java.time.Duration

/**
 * Drives queued publications to a terminal Exchange decision.
 *
 * The worker owns only the state machine and the remote conversation: outbox SQL lives in
 * [CatalogPublicationStore], namespace selection in [ExchangeNamespaceBinder], and credentials in
 * [ExchangeCredentialService]. Every remote call happens outside a database transaction, so a slow
 * Exchange can never hold a pooled connection or a row lock.
 *
 * Runs as a `single_owner` cluster task; the store's `SKIP LOCKED` claim and expiring lease keep it
 * correct anyway if a second node ever overlaps, and recover rows from a node that died mid-flight.
 */
@Component
class CatalogPublicationWorker(
    private val properties: ExchangeProperties,
    private val availability: ExchangeAvailability,
    private val client: ExchangeClient,
    private val credentials: ExchangeCredentialService,
    private val namespaceBinder: ExchangeNamespaceBinder,
    private val store: CatalogPublicationStore,
    private val metrics: ExchangeMetrics,
    private val jdbi: Jdbi,
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
        if (!availability.deploymentEnabled) return
        credentials.refreshExpiringConnections()
        store.claimDue(CLAIM_BATCH).forEach { publication ->
            // Pausing the tenant feature pauses its queue; it never fails or cancels work.
            if (!availability.isAvailable(publication.tenantKey)) {
                store.defer(publication.id, properties.setupRetryInterval, ExchangeFailureCode.FEATURE_PAUSED)
                return@forEach
            }
            runCatching { process(publication) }.onFailure { failure -> fail(publication, failure) }
        }
    }

    private fun process(publication: CatalogReleasePublication) {
        val namespace = publication.namespace
        val connection = credentials.activeConnection(publication.tenantKey)
        if (connection == null) {
            store.defer(publication.id, properties.setupRetryInterval, ExchangeFailureCode.NO_ACTIVE_CONNECTION)
            return
        }
        val token = credentials.accessToken(connection)
        if (token == null) {
            store.defer(publication.id, properties.setupRetryInterval, ExchangeFailureCode.NO_ACCESS_TOKEN)
            return
        }
        val response = if (publication.remotePublicationId == null) {
            // A binding made earlier is not proof of a present grant. Submitting into a namespace the
            // connection no longer holds earns a 403, which would mark the whole connection BLOCKED —
            // one catalog's stale binding taking down every other catalog in the tenant, and blaming
            // the wrong thing. Checking locally keeps it a per-publication problem that heals by
            // itself if the grant comes back. Only submission is gated: a publication Exchange has
            // already accepted must still be followed to its outcome.
            if (namespace !in grantedNamespaces(publication.tenantKey)) {
                store.defer(
                    publication.id,
                    properties.setupRetryInterval,
                    ExchangeFailureCode.NAMESPACE_NOT_GRANTED,
                    // The namespace is on the row being rendered, so it is not repeated here.
                )
                return
            }
            val archive = store.loadArchive(publication.id)
                ?: error("Publication ${publication.id} has no retained archive to submit")
            client.submit(connection.baseUrl, token, namespace, archive, publication.idempotencyKey)
        } else {
            // Following a submission spends no retry budget, because nothing has failed — which makes
            // this the one wait in the state machine with no natural end. Exchange validates and scans
            // on its own schedule, so a long wait is legitimate and the bound is generous; but a
            // submission it never decides would otherwise be polled for ever while holding its
            // retained archive, invisible except as a queue age that climbs without explanation.
            if (publication.submittedFor != null && publication.submittedFor > properties.submittedTimeout) {
                giveUpOnSubmission(publication)
                return
            }
            client.publication(connection.baseUrl, token, publication.remotePublicationId)
        }
        val status = CatalogPublicationStatus.fromRemote(response.state)
        metrics.submissionOutcome(status)
        if (status == CatalogPublicationStatus.ACCEPTED) {
            // Exchange now holds a release under these coordinates, and that outlives the local
            // catalog — so the fact is recorded on the binding rather than inferred from rows that
            // would disappear with it. Only acceptance means this: a submission Exchange takes and
            // then rejects has published nothing, and marking it here would freeze the namespace of
            // a catalog that never reached Exchange at all. Written before the row is updated, and
            // idempotent, so a crash in between re-marks on the next poll rather than losing it.
            jdbi.useHandle<Exception> { handle ->
                namespaceBinder.markPublished(handle, publication.tenantKey, publication.catalogKey)
            }
        }
        store.applyRemoteState(
            id = publication.id,
            status = status,
            remotePublicationId = response.id,
            // Exchange's own code and detail cross as data rather than being concatenated into
            // prose that then has to be split by eye to read back.
            code = if (status == CatalogPublicationStatus.REJECTED) ExchangeFailureCode.REJECTED_BY_EXCHANGE else null,
            detail = listOfNotNull(response.errorCode, response.errorDetail).joinToString(": ").ifBlank { null },
            pollDelay = properties.submittedPollInterval,
        )
    }

    private fun grantedNamespaces(tenantKey: TenantKey) = jdbi.withHandle<Set<String>, Exception> { handle ->
        namespaceBinder.grantedNamespaces(handle, tenantKey)
    }

    /**
     * Ends an unbounded wait on a submission Exchange took but never decided. `FAILED` rather than
     * silence: it is terminal for the worker, keeps the archive, and gives an administrator both
     * ways out — retry it, or withdraw it and release the bytes.
     */
    private fun giveUpOnSubmission(publication: CatalogReleasePublication) {
        store.abandon(publication.id, ExchangeFailureCode.SUBMISSION_UNDECIDED)
        logger.error(
            "Exchange publication {} was submitted as {} but undecided after {}; giving up",
            publication.id,
            publication.remotePublicationId,
            properties.submittedTimeout,
        )
    }

    /**
     * Records a transient failure with exponential backoff, and marks the connection when Exchange
     * says the problem is authorization rather than the payload.
     */
    private fun fail(publication: CatalogReleasePublication, failure: Throwable) {
        metrics.submissionError()
        when (failure) {
            // Not `failure.message`: that is the transport's own wording, and it ends up on the
            // settings page as the whole explanation of what an administrator should do.
            is HttpClientErrorException.Unauthorized ->
                credentials.markConnection(
                    publication.tenantKey,
                    ExchangeConnectionStatus.REAUTHORIZATION_REQUIRED,
                    credentials.authorizationFailure(failure),
                    failure.message,
                )

            // A refusal is ambiguous until we know whether it was this catalog's namespace or the
            // connection itself, and the local grant list cannot tell us — Exchange only writes it
            // when a tenant authorizes. So ask, and let the answer decide.
            is HttpClientErrorException.Forbidden -> if (withdrawnGrant(publication)) return

            // Exchange being unreachable says nothing about this publication — every queued release
            // in the installation is equally affected. Spending the attempt budget on it would turn
            // an outage into a pile of terminally failed publications an administrator has to retry
            // one by one, which is the opposite of what the budget is for.
            is ResourceAccessException -> {
                store.defer(
                    publication.id,
                    properties.setupRetryInterval,
                    ExchangeFailureCode.EXCHANGE_UNREACHABLE,
                    failure.message,
                )
                logger.warn("Exchange unreachable; publication {} waits without spending a retry", publication.id)
                return
            }

            else -> Unit
        }
        val status = store.recordFailure(
            id = publication.id,
            code = ExchangeFailureCode.SUBMISSION_FAILED,
            detail = failure.message ?: failure.javaClass.simpleName,
            attempts = publication.attempts,
            maxAttempts = properties.maxAttempts,
            delay = backoff(publication.attempts),
        )
        if (status == CatalogPublicationStatus.FAILED) {
            logger.error(
                "Exchange publication {} gave up after {} attempts: {}",
                publication.id,
                publication.attempts + 1,
                failure.message,
            )
        } else {
            logger.warn("Exchange publication {} failed, will retry: {}", publication.id, failure.message)
        }
    }

    /**
     * Handles a refusal that turns out to be about this catalog's namespace rather than the
     * connection: the publication waits and no retry is spent, the connection stays usable for every
     * other catalog, and the refreshed grant list means the pre-submission check catches it next time
     * instead of another refusal. Returns false when the connection itself was refused.
     */
    private fun withdrawnGrant(publication: CatalogReleasePublication): Boolean {
        val granted = credentials.refreshGrants(publication.tenantKey) ?: return false
        if (publication.namespace in granted) {
            credentials.markConnection(
                publication.tenantKey,
                ExchangeConnectionStatus.BLOCKED,
                ExchangeFailureCode.CONNECTION_REFUSED,
            )
            return false
        }
        store.defer(publication.id, properties.setupRetryInterval, ExchangeFailureCode.NAMESPACE_NOT_GRANTED)
        logger.warn(
            "Exchange withdrew namespace '{}' from tenant {}; publication {} is waiting",
            publication.namespace,
            publication.tenantKey,
            publication.id,
        )
        return true
    }

    /** 5s doubling per attempt, capped at an hour. */
    private fun backoff(attempts: Int): Duration = Duration.ofSeconds(minOf(3600L, 5L shl minOf(attempts, 9)))

    companion object {
        const val TASK_KEY = "core.exchange-catalog-publication"
        const val ROUTING_KEY = "system:core.exchange-catalog-publication"
        const val TASK_TYPE = "core.exchange-catalog-publication"
        private const val CLAIM_BATCH = 10
    }
}
