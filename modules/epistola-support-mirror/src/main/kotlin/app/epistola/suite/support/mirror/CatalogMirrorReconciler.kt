// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportRefusedException
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.connection.isInsufficientScope
import app.epistola.suite.support.connection.isSupportUnavailable
import app.epistola.suite.support.connection.lastContactFailed
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** What happened to one tenant in a round. */
enum class TenantReconcileResult {
    /** The complete set was sent and accepted; every release the mirror lacked was offered. */
    SENT,

    /** The set had not changed since it was last accepted, within a day: nothing was sent. */
    UNCHANGED,

    /** The tenant was withdrawn: the mirror confirmed it holds nothing of it. */
    WITHDRAWN,

    /** Not tried: backing off after failures, or the mirror cannot be used now. */
    SKIPPED,

    /**
     * Not tried: another round holds the tenant. That round ends before this one may start, so a
     * withdrawal never races a set being sent; a "Sync now" timer tries again shortly.
     */
    BUSY,

    /** The set was refused, or could not be built; recorded, retried later. */
    FAILED,

    /** The support service could not be reached; the round stops. */
    UNAVAILABLE,

    /**
     * The support service refused the `mirror` scope (`403 insufficient_scope`): Epistola withdrew
     * it. Nothing is recorded against the tenant, a withdrawal stays pending, and the round stops;
     * the mirror is used again once a check finds the scope granted.
     */
    NOT_GRANTED,

    /**
     * The support service refused this installation's registration (`invalid_client`), or no longer
     * knows it: nothing is the tenant's or a release's fault, so nothing is recorded, and the round
     * stops. Platform → Support connections shows the refusal.
     */
    REFUSED,
}

/** What a round did, per tenant key (as the wire names tenants). */
data class CatalogMirrorRoundReport(val ran: Boolean, val tenants: Map<String, TenantReconcileResult>)

/** The tenant shares more bindings than one request may carry. */
class TooManyMirrorBindingsException(tenantKey: TenantKey) : RuntimeException("Tenant ${tenantKey.value} shares more than ${CatalogMirror.MAX_BINDINGS} releases, more than the mirror takes in one set")

/**
 * Keeps the support service's custody copy in step with what tenants share (ADR 0051,
 * `docs/support-protocol.md` "Catalog mirror").
 *
 * A round asks which tenants the mirror holds, sends every sharing tenant's complete set of release
 * bindings, uploads one at a time each release the mirror says it lacks, and withdraws (sends an
 * empty set for) every tenant it holds that does not share — opted out, deleted, never opted in.
 *
 * Database work happens in short transactions of its own (mediator queries, [CatalogMirrorStateStore]);
 * no transaction is open while the support service is called. Does nothing unless the support
 * connection is on and Epistola granted the `mirror` scope. With the mirror turned off
 * (`epistola.support.mirror.enabled=false`) nothing is sent, but withdrawals still run: what a
 * tenant stopped sharing is deleted at Epistola either way.
 */
@Component
class CatalogMirrorReconciler(
    private val registry: SupportConnectionRegistry,
    private val properties: CatalogMirrorProperties,
    private val client: CatalogMirrorClient,
    private val store: CatalogMirrorStateStore,
    private val mediator: Mediator,
    private val privacy: SupportPrivacy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The mirror may be used now over [connectionId]: gated by configuration and by the scope that connection granted. */
    fun usable(connectionId: UUID): Boolean = registry.find(connectionId)?.let { Round(it).usable() } ?: false

    /**
     * One scheduled round over every connection the mirror is selected for (ADR 0053 step 3i): each
     * with its own tenants' choices, state and back-off, so one connection that fails or refuses
     * never holds back another.
     */
    fun reconcileEvery(): Map<UUID, CatalogMirrorRoundReport> = registry.all().filter { it.isSelected(SupportScope.MIRROR) }.associate { connection ->
        connection.id to try {
            Round(connection).reconcileAll()
        } catch (e: Exception) {
            log.warn("Catalog mirror: the round for support connection {} failed", connection.id, e)
            CatalogMirrorRoundReport(ran = false, tenants = emptyMap())
        }
    }

    /** One round over every tenant with [connectionId]; with the mirror turned off, only the withdrawals. */
    fun reconcileAll(connectionId: UUID): CatalogMirrorRoundReport = registry.find(connectionId)?.let { Round(it).reconcileAll() } ?: CatalogMirrorRoundReport(ran = false, tenants = emptyMap())

    /** "Sync now" for one tenant with [connectionId]: its set is sent whatever changed, or the tenant withdrawn when it does not share. */
    fun syncTenant(tenantKey: TenantKey, connectionId: UUID): TenantReconcileResult {
        val connection = registry.find(connectionId) ?: return TenantReconcileResult.SKIPPED.also { store.clearSyncRequest(connectionId, tenantKey) }
        return Round(connection).syncTenant(tenantKey)
    }

    /** Another round with [connectionId] holds [tenantKey] now (a claim within its lease, by the database's clock). */
    fun isClaimed(tenantKey: TenantKey, connectionId: UUID): Boolean = store.isClaimed(connectionId, tenantKey)

    /** The round interval, doubled per failure in a row, at most [CatalogMirror.MAX_BACKOFF]. */
    internal fun backoff(failures: Int): Duration = backoff(properties.interval, failures)

    private fun <T> withMediator(block: () -> T): T = MediatorContext.runWithMediator(mediator, block)

    /** The reconcile with one [connection]: its client calls, its state rows, its privacy. */
    private inner class Round(val connection: SupportConnection) {
        private val id: UUID = connection.id

        /** The mirror may be used now: gated by configuration and by the scope the connection granted. */
        fun usable(): Boolean = properties.enabled && reachable()

        /** The connection's mirror can be called at all: it holds the `mirror` scope (Epistola support's only while it is on). */
        private fun reachable(): Boolean = connection.hasScope(SupportScope.MIRROR)

        /** One scheduled round over every tenant; with the mirror turned off, only the withdrawals. */
        fun reconcileAll(): CatalogMirrorRoundReport {
            if (!reachable()) return CatalogMirrorRoundReport(ran = false, tenants = emptyMap())
            if (connection.lastContactFailed()) {
                log.debug("Catalog mirror: the support service was unreachable at the last contact; skipping this round")
                return CatalogMirrorRoundReport(ran = false, tenants = emptyMap())
            }
            val held = try {
                client.heldTenants(connection).toSet()
            } catch (e: Exception) {
                if (e.isRegistrationRefused()) {
                    registrationRefused()
                    return CatalogMirrorRoundReport(ran = false, tenants = emptyMap())
                }
                if (e.isInsufficientScope()) {
                    notGranted()
                    return CatalogMirrorRoundReport(ran = false, tenants = emptyMap())
                }
                if (!e.isSupportUnavailable()) throw e
                log.info("Catalog mirror: support service unavailable ({}); trying next round", e.message)
                return CatalogMirrorRoundReport(ran = false, tenants = emptyMap())
            }
            val local = withMediator { ListMirrorTenants(id).query() }
            // Confidential tenants (ADR 0052 §1): nothing is shared, and whatever the mirror holds is withdrawn.
            val confidential = privacy.forConnection(id).confidential
            val sharing = if (confidential) emptySet() else local.sharing.map { it.value }.toSet()
            val toWithdraw = (held - sharing) + local.withdrawalPending.map { it.value }
            val report = linkedMapOf<String, TenantReconcileResult>()
            val deadline = deadline()
            // Withdrawals first: they are cheap, and what a tenant asked to have deleted never waits
            // behind uploads, a slow service or another tenant's failure.
            for (tenant in toWithdraw.sorted()) {
                val result = guarded(tenant) { withdraw(tenant) }
                report[tenant] = result
                if (result.stopsRound()) return CatalogMirrorRoundReport(ran = true, tenants = report)
            }
            for (tenant in if (confidential) emptyList() else local.sharing) {
                // usable() again per tenant: deselected or turned off meanwhile, no further set leaves.
                if (!usable() || System.nanoTime() > deadline) break
                val result = guarded(tenant.value) { reconcileTenant(tenant, forced = false, held = tenant.value in held, deadline = deadline) }
                report[tenant.value] = result
                if (result.stopsRound()) break
            }
            return CatalogMirrorRoundReport(ran = true, tenants = report)
        }

        /** "Sync now" for one tenant: its set is sent whatever changed, or the tenant withdrawn when it does not share. */
        fun syncTenant(tenantKey: TenantKey): TenantReconcileResult {
            // Confidential: no request names a tenant by its key. What the mirror still holds goes in the
            // regular round, under the keys the mirror itself lists.
            if (privacy.forConnection(id).confidential) {
                store.clearSyncRequest(id, tenantKey)
                return TenantReconcileResult.SKIPPED
            }
            val sharing = tenantKey in withMediator { ListMirrorTenants(id).query() }.sharing
            if (!reachable() || (sharing && !properties.enabled)) {
                store.clearSyncRequest(id, tenantKey)
                return TenantReconcileResult.SKIPPED
            }
            return guarded(tenantKey.value) { if (sharing) reconcileTenant(tenantKey, forced = true, held = true, deadline = deadline()) else withdraw(tenantKey.value) }
        }

        /** When a round must stop uploading: [CatalogMirrorProperties.roundBudget] from now, in [System.nanoTime]. */
        private fun deadline(): Long = System.nanoTime() + properties.roundBudget.toNanos()

        /**
         * One tenant's work, never failing the round: anything unexpected (a release that cannot be
         * exported, say) is recorded against that tenant with a back-off, and the round goes on.
         */
        private fun guarded(tenant: String, block: () -> TenantReconcileResult): TenantReconcileResult = try {
            block()
        } catch (e: Exception) {
            if (e.isRegistrationRefused()) return registrationRefused()
            log.warn("Catalog mirror: unexpected failure for tenant {}", tenant, e)
            val key = TenantKey.validateOrNull(tenant)
            if (key != null && store.exists(key)) fail(key, "Unexpected failure: ${describe(e)}", EpistolaClock.offsetDateTime()) else TenantReconcileResult.FAILED
        }

        /**
         * Sends [tenantKey]'s set and the releases the mirror lacks. [held] is whether the mirror listed
         * the tenant this round: one it no longer holds is sent again even when nothing changed here.
         */
        private fun reconcileTenant(tenantKey: TenantKey, forced: Boolean, held: Boolean, deadline: Long): TenantReconcileResult {
            val now = EpistolaClock.offsetDateTime()
            var claim = store.claim(id, tenantKey) ?: return TenantReconcileResult.BUSY
            try {
                val round = store.round(id, tenantKey)
                if (!forced && round?.nextAttemptAt?.isAfter(now) == true) return TenantReconcileResult.SKIPPED
                val bindings = try {
                    bindings(tenantKey)
                } catch (e: TooManyMirrorBindingsException) {
                    return fail(tenantKey, e.message!!, now)
                }
                val set = client.tenantSet(bindings)
                if (!forced && (held || bindings.isEmpty()) && unchanged(tenantKey, round, set, now)) return TenantReconcileResult.UNCHANGED

                val response = try {
                    client.putTenant(connection, tenantKey.value, set)
                } catch (e: Exception) {
                    return failed(tenantKey, "Sending the set of ${bindings.size} release(s) failed: ${describe(e)}", e, now)
                }
                var uploaded = 0
                val failures = mutableListOf<CatalogMirrorFailedRelease>()
                // A release that failed before waits for its own back-off: it is not rebuilt and sent every round.
                val earlier = round?.failedReleases.orEmpty().associateBy { it.contentHash }
                fun failedAgain(hash: String, error: String): CatalogMirrorFailedRelease {
                    val attempts = (earlier[hash]?.attempts ?: 0) + 1
                    return CatalogMirrorFailedRelease(hash, error, attempts, EpistolaClock.offsetDateTime().plus(backoff(attempts)))
                }
                val missing = response.missing.distinct()
                for ((index, hash) in missing.withIndex()) {
                    // Opted out while this round ran: nothing more leaves, and the round withdraws what it sent.
                    if (!store.shares(id, tenantKey)) return withdrawClaimed(tenantKey, now)
                    // Deselected (on any node: seen here within a second), turned off, or no longer granted
                    // while this round ran: nothing more leaves; the rest stays missing for a later round.
                    if (!usable()) {
                        log.info("Catalog mirror: tenant {}: the mirror can no longer be used (deselected, turned off or not granted); {} release(s) not uploaded", tenantKey.value, missing.size - index)
                        missing.drop(index).forEach { left -> failures += earlier[left] ?: CatalogMirrorFailedRelease(left, "Not uploaded: the mirror could no longer be used", attempts = 0) }
                        break
                    }
                    val waiting = earlier[hash]?.takeIf { it.nextAttemptAt?.isAfter(EpistolaClock.offsetDateTime()) == true }
                    if (waiting != null) {
                        failures += waiting
                        continue
                    }
                    // Out of time: the rest stays missing and goes next round.
                    if (System.nanoTime() > deadline) {
                        log.info("Catalog mirror: tenant {}: round budget spent; {} release(s) left for the next round", tenantKey.value, missing.size - index)
                        missing.drop(index).forEach { left -> failures += earlier[left] ?: CatalogMirrorFailedRelease(left, "Not uploaded yet: left for the next round", attempts = 0) }
                        break
                    }
                    val binding = bindings.firstOrNull { it.contentHash == hash }
                    if (binding == null) {
                        failures += failedAgain(hash, "Asked for a release this tenant does not share")
                        continue
                    }
                    // No longer sent (its catalog, or one a release it belongs to depends on, was marked
                    // "Never share" while this round ran): not exported or sent. The next round's set
                    // leaves it out, and the mirror deletes what it holds of it.
                    if (!store.stillSends(id, tenantKey, binding.versionRowId)) continue
                    val archive = try {
                        export(tenantKey, binding)
                    } catch (e: CatalogReleaseNotFoundException) {
                        // Deleted since the set was read; the next round's set will not name it.
                        continue
                    } catch (e: Exception) {
                        failures += failedAgain(hash, "Could not be exported: ${e.message ?: e.javaClass.simpleName}")
                        continue
                    }
                    if (archive.size > CatalogMirror.MAX_ARCHIVE_BYTES) {
                        failures += failedAgain(hash, "The archive is ${archive.size} bytes, more than the mirror takes")
                        continue
                    }
                    try {
                        client.putRelease(connection, hash, archive)
                        uploaded++
                    } catch (e: Exception) {
                        val what = "Uploading ${binding.catalog.namespace}/${binding.catalog.key.value} ${binding.version} failed: ${describe(e)}"
                        when {
                            e.isRegistrationRefused() -> return registrationRefused()

                            // The service failed on this release alone (a `500`): as refused, it waits on
                            // its own and the round goes on; the releases behind it still go.
                            e.isServerErrorOnItem() -> failures += failedAgain(hash, describe(e))

                            e.isSupportUnavailable() || e.isInsufficientScope() -> return failed(tenantKey, what, e, now)

                            // Refused for a reason that holds for every release (a quota, a fingerprint version
                            // the service cannot compute, the token): stop, and back the tenant off.
                            e.refusesEveryRelease() -> return fail(tenantKey, what, now, atLeast = SYSTEMIC_REFUSAL_BACKOFF)

                            else -> failures += failedAgain(hash, describe(e))
                        }
                    }
                    // Another round took the tenant over (this one outlived its lease): stop, and leave it to that one.
                    claim = store.renew(id, tenantKey, claim) ?: run {
                        log.warn("Catalog mirror: tenant {}: another round took the tenant over; stopping this one", tenantKey.value)
                        return TenantReconcileResult.BUSY
                    }
                }
                if (!store.shares(id, tenantKey)) return withdrawClaimed(tenantKey, now)
                store.recordAccepted(id, tenantKey, CatalogMirrorRoundOutcome(set.digest, bindings.map { it.catalogRowId }.toSet(), bindings.size, uploaded, failures), EpistolaClock.offsetDateTime())
                log.info(
                    "Catalog mirror: tenant {} sent {} binding(s), {} release(s) uploaded, {} removed there, {} failed",
                    tenantKey.value,
                    bindings.size,
                    uploaded,
                    response.removed,
                    failures.size,
                )
                return TenantReconcileResult.SENT
            } finally {
                store.release(id, tenantKey, claim)
            }
        }

        /**
         * Sends an empty set for [tenant]: the mirror deletes what it held of it. A tenant deleted here
         * has no state to record; for one that exists, the opt-out is confirmed. A tenant that exists is
         * withdrawn only under its claim, so a round still sending its set cannot put it back afterwards.
         */
        private fun withdraw(tenant: String): TenantReconcileResult {
            val now = EpistolaClock.offsetDateTime()
            val key = TenantKey.validateOrNull(tenant)
            val claim = key?.let { store.claim(id, it) }
            if (key != null && claim != null) {
                try {
                    return withdrawClaimed(key, now)
                } finally {
                    store.release(id, key, claim)
                }
            }
            if (key != null && store.exists(key)) return TenantReconcileResult.BUSY
            return sendWithdrawal(tenant, local = null, now)
        }

        /** Withdraws [tenantKey], whose claim the caller holds. */
        private fun withdrawClaimed(tenantKey: TenantKey, now: OffsetDateTime): TenantReconcileResult = sendWithdrawal(tenantKey.value, tenantKey, now)

        /** Sends the empty set; records the outcome for [local], the claimed tenant, when there is one. */
        private fun sendWithdrawal(tenant: String, local: TenantKey?, now: OffsetDateTime): TenantReconcileResult {
            val response = try {
                client.putTenant(connection, tenant, client.tenantSet(emptyList()))
            } catch (e: Exception) {
                if (e.isInsufficientScope()) return notGranted()
                if (e.isRegistrationRefused()) return registrationRefused()
                if (local != null) return failed(local, "Withdrawing the tenant failed: ${describe(e)}", e, now)
                log.warn("Catalog mirror: withdrawing tenant {} failed: {}", tenant, describe(e))
                return if (e.isSupportUnavailable()) TenantReconcileResult.UNAVAILABLE else TenantReconcileResult.FAILED
            }
            if (response.bindings != 0) {
                val message = "The mirror still holds ${response.bindings} binding(s) after the withdrawal"
                if (local != null) return fail(local, message, now)
                log.warn("Catalog mirror: tenant {}: {}", tenant, message)
                return TenantReconcileResult.FAILED
            }
            if (local != null) store.recordWithdrawn(id, local, EpistolaClock.offsetDateTime())
            log.info("Catalog mirror: tenant {} withdrawn ({} binding(s) removed there)", tenant, response.removed)
            return TenantReconcileResult.WITHDRAWN
        }

        /** Every binding the tenant shares, read a page at a time in short transactions. */
        private fun bindings(tenantKey: TenantKey): List<MirrorBinding> {
            val all = mutableListOf<MirrorBinding>()
            var cursor: MirrorBindingCursor? = null
            do {
                val page = withMediator { ListMirrorBindings(tenantKey, cursor, connectionId = id).query() }
                all += page.bindings
                if (all.size > CatalogMirror.MAX_BINDINGS) throw TooManyMirrorBindingsException(tenantKey)
                cursor = page.next
            } while (cursor != null)
            return all
        }

        /** Unchanged since it was last accepted in full, and that was less than a day ago. */
        private fun unchanged(tenantKey: TenantKey, round: CatalogMirrorRound?, set: MirrorTenantSet, now: OffsetDateTime): Boolean {
            val lastSuccess = round?.lastSuccessAt ?: return false
            if (round.releasesMissing > 0 || round.consecutiveFailures > 0) return false
            if (lastSuccess.plus(CatalogMirror.FULL_RECONCILE_INTERVAL).isBefore(now)) return false
            return store.lastDigest(id, tenantKey) == set.digest
        }

        /** The release archive, built under the tenant's system principal in a database-only transaction. */
        private fun export(tenantKey: TenantKey, binding: MirrorBinding): ByteArray = withMediator {
            SecurityContext.runWithPrincipal(SystemUser.principalForTenant(tenantKey)) {
                ExportCatalogZip(binding.catalog, binding.version).execute().zipBytes
            }
        }

        /**
         * Records a failed call: an unavailable service stops the round, anything else only this
         * tenant's. A refused `mirror` scope is not the tenant's failure: nothing is recorded.
         */
        private fun failed(tenantKey: TenantKey, message: String, e: Exception, now: OffsetDateTime): TenantReconcileResult {
            if (e.isInsufficientScope()) return notGranted()
            if (e.isRegistrationRefused()) return registrationRefused()
            if (!e.isSupportUnavailable() && e !is RestClientResponseException) log.warn("Catalog mirror: unexpected failure for tenant {}", tenantKey.value, e)
            fail(tenantKey, message, now)
            return if (e.isSupportUnavailable()) TenantReconcileResult.UNAVAILABLE else TenantReconcileResult.FAILED
        }

        /** Tells the connection the service refused the `mirror` scope, so no node retries until a check finds it granted. */
        private fun notGranted(): TenantReconcileResult {
            connection.scopeRefused(SupportScope.MIRROR)
            log.info("Catalog mirror: the support service refused the mirror scope; stopping until it is granted again")
            return TenantReconcileResult.NOT_GRANTED
        }

        /** The connection already recorded and shows the refusal; the mirror only stops. */
        private fun registrationRefused(): TenantReconcileResult {
            log.info("Catalog mirror: the support service refused this installation's registration; stopping the round")
            return TenantReconcileResult.REFUSED
        }

        /** The registration was refused (or is gone) at the token endpoint, possibly mid-request. */
        private fun Throwable.isRegistrationRefused(): Boolean = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(16).any { it is SupportRefusedException }

        /** A `500`: the service failed on this one request, not as a whole (`docs/support-protocol.md`, "Reliability"). */
        private fun Throwable.isServerErrorOnItem(): Boolean = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(16)
            .any { it is RestClientResponseException && it.statusCode.value() == 500 }

        /**
         * A refusal that holds for every release of the installation, not for this one: `507
         * quota_exceeded`, `422 unsupported_hash`, `401`, or a `403` other than a missing scope.
         */
        private fun Exception.refusesEveryRelease(): Boolean {
            val response = this as? RestClientResponseException ?: return false
            return when (response.statusCode.value()) {
                401, 403, 507 -> true
                422 -> response.responseBodyAsString.contains("unsupported_hash")
                else -> false
            }
        }

        private fun TenantReconcileResult.stopsRound(): Boolean = this == TenantReconcileResult.UNAVAILABLE || this == TenantReconcileResult.NOT_GRANTED || this == TenantReconcileResult.REFUSED

        private fun fail(tenantKey: TenantKey, message: String, now: OffsetDateTime, atLeast: Duration = Duration.ZERO): TenantReconcileResult {
            val failures = store.consecutiveFailures(id, tenantKey) + 1
            store.recordFailure(id, tenantKey, message, now, now.plus(maxOf(backoff(failures), atLeast)))
            log.warn("Catalog mirror: tenant {}: {}", tenantKey.value, message)
            return TenantReconcileResult.FAILED
        }

        private fun describe(e: Exception): String = when (e) {
            is RestClientResponseException -> "${e.statusCode.value()} ${e.responseBodyAsString.take(300)}".trim()
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    companion object {
        /** The least a tenant waits after a refusal that holds for every release (a quota, an unsupported fingerprint): a person has to act. */
        val SYSTEMIC_REFUSAL_BACKOFF: Duration = Duration.ofHours(1)

        /** [interval] doubled per failure in a row beyond the first, at most [CatalogMirror.MAX_BACKOFF]. */
        fun backoff(interval: Duration, failures: Int): Duration {
            val doublings = (failures - 1).coerceIn(0, 20)
            val delay = interval.multipliedBy(1L shl doublings)
            return if (delay > CatalogMirror.MAX_BACKOFF) CatalogMirror.MAX_BACKOFF else delay
        }
    }
}
