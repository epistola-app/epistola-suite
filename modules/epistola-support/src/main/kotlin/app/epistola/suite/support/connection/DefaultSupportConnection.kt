// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.productdomain.ProductDiscovery
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.time.EpistolaClock
import com.nimbusds.jose.jwk.ECKey
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The [SupportConnection] (`docs/support-protocol.md`).
 *
 * - **Registration and status** happen in [checkNow], under a cluster-wide lock, so one node
 *   registers and every node reads the result from the connection's row. The check registers on its own
 *   only with `epistola.support.auto-register`; otherwise an installation administrator registers
 *   with [registerNow].
 * - **Expiry**: the service deletes a registration nobody activated within seven days of issuing its
 *   claim code. A check that finds it gone ([SupportRegistrationGoneException]) forgets it and
 *   registers again at once with auto-register, or records [SupportConnectionState.Expired].
 * - **Tokens** are per node: each node signs its own assertions with the stored key and caches
 *   its token; [refreshTokenIfDue] keeps one fresh while the connection is in use, so
 *   [cachedAccessToken] has something to return.
 * - **[status]** never touches the network: it keeps the stored rows while their version
 *   ([SupportConnectionStore.version]) is unchanged, checking it at most once a second and reading
 *   them all again after 30 seconds regardless, and lays over them what this node learned since —
 *   the scopes of a newer token, a failure. A change any node stores reaches every node within the
 *   second; no broadcast is needed.
 * - **Services**: the installation uses a service only while an administrator selected it
 *   ([saveSelectedServices], or `epistola.support.services` until one did) **and** Epistola granted
 *   it ([hasScope]). Tokens ask for exactly the selected services the service offers
 *   (`scope=`, empty for none), so a deselected service loses its scope at the next token too.
 * - **Failures back off** ([SupportBackoff]): after a failed call this node does not ask for a token again
 *   for a while (30 seconds, doubling to five minutes), and [accessToken] fails at once instead of
 *   calling; a call already fetching a token is never queued behind. Only [checkNow] probes during
 *   the backoff, and a token it gets ends it.
 * - **A `401` on a call** means the service no longer accepts this node's token (its signing key
 *   changed, it restarted): the token is replaced at once ([tokenRejected], and the [restClient]'s
 *   retry), at most once per back-off of its own (30 seconds, doubling to five minutes) until a call
 *   shows a token accepted, so a service that refuses every token never gets a token request per call.
 * - **Saving a selection** drops this node's token, so nothing is sent with a token that does not
 *   carry what was selected; the next call, or the token refresh, asks for one with the new selection.
 */
@Component
class DefaultSupportConnection internal constructor(
    private val properties: SupportProperties,
    /** What this connection is configured with: Epistola support's from `epistola.support.*`, another's from its row. */
    internal val settings: RegisteringConnectionSettings,
    internal val store: SupportConnectionStore,
    private val client: SupportServiceClient,
    private val productDiscovery: ProductDiscovery,
    private val installations: InstallationService,
    private val installation: InstallationProperties,
    private val nodeIdentity: NodeIdentity,
    private val buildProperties: BuildProperties?,
    private val supportHttp: SupportHttp,
) : SupportConnection {
    /** Epistola support's connection, configured by `epistola.support.*`: the bean every module injects. */
    @Autowired
    constructor(
        properties: SupportProperties,
        store: SupportConnectionStore,
        client: SupportServiceClient,
        productDiscovery: ProductDiscovery,
        installations: InstallationService,
        installation: InstallationProperties,
        nodeIdentity: NodeIdentity,
        buildProperties: BuildProperties?,
        supportHttp: SupportHttp,
    ) : this(properties, RegisteringConnectionSettings.of(properties), store, client, productDiscovery, installations, installation, nodeIdentity, buildProperties, supportHttp)

    private val log = LoggerFactory.getLogger(javaClass)

    override val id: UUID get() = store.connectionId

    @Volatile private var snapshot: SupportRowsSnapshot? = null

    /** The selection the last read of the stored rows found; another node changing it drops this node's token. */
    @Volatile private var lastReadServices: List<String>? = null

    /** Bumped by [invalidate]: a read that started before it is used once, never cached. */
    private var snapshotGeneration = 0L
    private val snapshotLock = Any()

    @Volatile private var token: SupportCachedToken? = null

    @Volatile private var localFailure: SupportNodeFailure? = null

    /** Whether this node's clock is off, and its stored clock error ([SupportNodeClock]). */
    private val nodeClock = SupportNodeClock(store, nodeIdentity.nodeId, log)

    @Volatile private var backoff: SupportBackoff? = null

    private val metadataCache = SupportMetadataCache(client, properties.allowHttp)

    @Volatile private var rest: Pair<URI, RestClient>? = null

    /** Tokens replaced after a `401`, in a row without a token shown accepted; one entry, so bounded. */
    @Volatile private var rejections: SupportBackoff? = null
    private val rejectionLock = Any()

    /** A background renewal after a `401` is under way; at most one at a time. */
    private val renewing = AtomicBoolean()

    @Volatile private var renewal: Thread? = null

    init {
        val unknown = settings.configuredServices.filter { it !in SupportScope.NAMES }
        if (settings.enabled && unknown.isNotEmpty()) {
            log.warn(
                "epistola.support.services names {} which this version does not know (it knows {}); kept, but not used",
                unknown,
                SupportScope.NAMES.sorted(),
            )
        }
    }

    private val tokenLock = ReentrantLock()

    override fun status(): SupportConnectionStatus {
        if (!settings.enabled) return SupportConnectionStatus(SupportConnectionState.Disabled, null, null, null)
        val snap = snapshot()
        val stored = snap.status
        val credentials = snap.credentials?.takeIf { it.clientId != null && (snap.issuer == null || sameIssuer(it.issuer, snap.issuer)) }
        val clientId = credentials?.clientId
        val current = stored?.takeIf { clientId != null && it.clientId == clientId }

        var state: SupportConnectionState = when {
            // Before the credentials: they may outlive the record for a moment (SupportConnectionStore.disconnected).
            stored?.state == StoredConnectionState.DISCONNECTED ->
                SupportConnectionState.Disconnected(stored.disconnectedAt ?: stored.checkedAt ?: Instant.EPOCH, stored.disconnectedBy, stored.disconnectOutcome ?: SupportDisconnectOutcome.LOCAL_ONLY)

            clientId == null -> stored?.takeIf { snap.credentials == null && it.state == StoredConnectionState.EXPIRED }?.clientId
                ?.let { SupportConnectionState.Expired(it, stored.claimCodeExpiresAt) }
                ?: SupportConnectionState.Unregistered

            current?.state == StoredConnectionState.REFUSED -> SupportConnectionState.Refused(clientId, current.reason ?: "refused")

            current?.state == StoredConnectionState.ACTIVE -> SupportConnectionState.Active(clientId, current.scopes.mapNotNull(SupportScope::parse).toSet(), current.pause?.toPause())

            current?.state == StoredConnectionState.DECLINED -> SupportConnectionState.Declined(clientId, current.declinedAt)

            else -> SupportConnectionState.Pending(clientId, credentials.claimCode.orEmpty(), current?.claimCodeExpiresAt, current?.replacedExpiredAt)
        }
        // A token this node obtained after the scopes were last learned is newer news about them.
        val newer = token?.takeIf { it.clientId == clientId && newerThanScopes(it, current) }
        if (newer != null) {
            state = when (state) {
                is SupportConnectionState.Pending -> if (newer.scopes.isEmpty()) state else SupportConnectionState.Active(state.clientId, newer.scopes, null)

                // Only about what it asked for: a scope it did not ask for keeps what the check found. A
                // paused scope is left out of tokens, so a token's silence about it is no withdrawal.
                is SupportConnectionState.Active -> state.copy(scopes = (state.scopes - newer.requested) + newer.scopes + state.scopes.intersect(state.pause?.takeIf { it.inForceAt(EpistolaClock.instant()) }?.scopes.orEmpty()))

                else -> state
            }
        }
        // A scope the service refused on any node, unless this node's token was asked for after the
        // refusal and carries it again. A check that learned the grants since dropped the refusal.
        if (state is SupportConnectionState.Active) {
            val current = token?.takeIf { it.clientId == clientId }
            val refused = snap.refusals.filter { (scope, seq) -> !(current != null && current.seq >= seq && scope in current.scopes) }.keys
            if (refused.isNotEmpty()) state = state.copy(scopes = state.scopes - refused)
        }
        val failure = localFailure?.takeIf { stored?.checkedAt == null || it.at.isAfter(stored.checkedAt) }
        val nodeWarning = snap.nodeClocks.values.joinToString(" ").ifEmpty { null }
        return SupportConnectionStatus(state, snap.issuer, stored?.checkedAt, failure?.message ?: stored?.lastError, nodeWarning)
    }

    /**
     * This node's clock is off, so the support service refuses its client assertions: it does not
     * take on cluster-wide support work ([SupportClusterCapability]) until a token is fetched again.
     */
    fun nodeClockOff(): Boolean = settings.enabled && nodeClock.isOff()

    /**
     * Whether [t] was asked for after [stored]'s scopes were learned: by the store version when the
     * check recorded it, which orders nodes whose clocks differ; by instants for rows without it.
     */
    private fun newerThanScopes(t: SupportCachedToken, stored: StoredSupportStatus?): Boolean {
        stored?.scopesVersion?.let { return t.seq > it }
        val scopesAt = stored?.scopesAt() ?: return true
        return t.obtainedAt.isAfter(scopesAt)
    }

    override fun hasScope(scope: SupportScope): Boolean = isSelected(scope) &&
        offeredServices()?.contains(scope.value) != false &&
        (status().state as? SupportConnectionState.Active)?.usable(EpistolaClock.instant())?.contains(scope) == true

    override fun isSelected(scope: SupportScope): Boolean = settings.enabled && scope.value in selectedServices().services

    override fun scopeRefused(scope: SupportScope) {
        if (!settings.enabled) return
        val current = token
        if (current != null && scope !in current.scopes) {
            // A token from before the scope was asked for or granted: drop it, the next call asks again.
            if (token === current) token = null
            return
        }
        // Already refused after this token was asked for: stored, and in force on every node.
        val previous = snapshot().refusals[scope]
        if (previous != null && (current == null || previous > current.seq)) return
        try {
            store.recordRefusal(scope.value)
        } catch (e: RuntimeException) {
            // Not shared: at least this node stops using the token; the next one says what is granted.
            log.warn("Support connection: could not store that the support service refused the {} scope: {}", scope.value, e.message)
            if (token === current) token = null
            return
        } finally {
            invalidate()
        }
        log.info("Support connection: the support service refused the {} scope (Epistola withdrew it); not using {} on any node until a check or a newer token finds it granted again", scope.value, scope.value)
    }

    /**
     * The services selected now: the saved selection, or `epistola.support.services` until an
     * installation administrator saved one. Cached with the other stored rows; no network I/O.
     */
    fun selectedServices(): SupportServiceSelection {
        if (!settings.enabled) return SupportServiceSelection(settings.configuredServices, fromConfiguration = true, savedAt = null, savedBy = null)
        val stored = snapshot().services
        return if (stored == null) {
            SupportServiceSelection(settings.configuredServices, fromConfiguration = true, savedAt = null, savedBy = null)
        } else {
            SupportServiceSelection(SupportScope.normalize(stored.services), fromConfiguration = false, savedAt = stored.savedAt, savedBy = stored.savedBy)
        }
    }

    /**
     * Saves the selection of services, installation-wide: from now on it wins over
     * `epistola.support.services`. No network I/O. A deselected service stops at once on this node,
     * and within a second on every other node, which also drops its token; the next token asks for
     * the new selection. Names this version does not know are kept.
     */
    fun saveSelectedServices(services: Collection<String>, savedBy: String?): SupportServiceSelection {
        store.saveServices(StoredSupportServices(SupportScope.normalize(services), EpistolaClock.instant(), savedBy))
        invalidate()
        // The token asked for the old selection: a newly selected service would be refused with it
        // (403 insufficient_scope). The next call, or the token refresh, asks for the new one.
        token = null
        // Inside a transaction (the command's), a read on another thread until it ends sees the old
        // row, and a token fetched meanwhile asks for the old selection: forget both once it commits
        // (or rolls back).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        invalidate()
                        token = null
                    }
                },
            )
        }
        log.info("Support connection: services selected: {}", SupportScope.normalize(services).ifEmpty { listOf("none") })
        return selectedServices()
    }

    override fun offeredServices(): Set<String>? = metadataCache.cached()?.scopesSupported

    /** The scopes a token asks for now: selected, known to this suite, and offered by the service when it says what it offers. */
    private fun wantedScopes(serviceMetadata: SupportServiceMetadata?): Set<SupportScope> {
        val selected = selectedServices().services.mapNotNull(SupportScope::parse).toSet()
        val offered = serviceMetadata?.scopesSupported ?: return selected
        return selected.filter { it.value in offered }.toSet()
    }

    override fun apiEndpoint(): URI? = metadataCache.cached()?.apiEndpoint

    override fun otlpEndpoint(): URI? = metadataCache.cached()?.otlpEndpoint

    override fun requestsEndpoint(): URI? = metadataCache.cached()?.requestsEndpoint

    override fun cachedAccessToken(): String? {
        if (!settings.enabled) return null
        val current = token?.takeIf { it.expiresAt.isAfter(EpistolaClock.instant().plus(CACHED_MARGIN)) } ?: return null
        if (!fitsNow(current)) {
            // Asked for another selection, or lacks a scope granted since: never sent with; a new one
            // is fetched in the background, and the caller (telemetry) drops this batch.
            renewInBackground()
            return null
        }
        // Only for the registration in use: not after it was refused or forgotten, on any node.
        val clientId = when (val state = status().state) {
            is SupportConnectionState.Pending -> state.clientId
            is SupportConnectionState.Active -> state.clientId
            else -> null
        }
        return current.value.takeIf { current.clientId == clientId }
    }

    override fun accessToken(): String {
        if (!settings.enabled) throw SupportUnavailableException(settings.offReason)
        // Refused: asking again only gets invalid_client again. A check (or Register again) finds a restored registration.
        (status().state as? SupportConnectionState.Refused)?.let { throw SupportRefusedException("The support service refused registration ${it.clientId}: ${it.reason}") }
        usable()?.let { return it.value }
        backingOff()?.let { throw SupportUnavailableException("Not contacting the support service until ${it.until}, after ${it.failures} failed call(s) in a row") }
        // Never queue behind another thread's token request: it may hang until its deadline.
        if (!tokenLock.tryLock(TOKEN_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw SupportUnavailableException("Another request for a token from the support service is still running")
        }
        return try {
            usable()?.value ?: fetchToken().value
        } finally {
            tokenLock.unlock()
        }
    }

    /** The backoff in force on this node now, or null. */
    private fun backingOff(): SupportBackoff? = backoff?.takeIf { it.until.isAfter(EpistolaClock.instant()) }

    /**
     * A call to the support service failed in a way that means "not now": recorded as this node's
     * last error (which closes the gates of the services, `lastContactFailed`) and backed off.
     */
    private fun noteUnavailable(message: String) {
        val now = EpistolaClock.instant()
        localFailure = SupportNodeFailure(now, message)
        val failures = (backoff?.failures ?: 0) + 1
        backoff = SupportBackoff(failures, now.plus(backoffDelay(failures)))
    }

    /** 30 seconds after the first failure in a row, doubling to five minutes. */
    private fun backoffDelay(failures: Int): Duration = BACKOFF_FIRST.multipliedBy(1L shl (failures - 1).coerceAtMost(BACKOFF_MAX_DOUBLINGS)).coerceAtMost(BACKOFF_MAX)

    override fun tokenRejected(token: String) {
        if (!settings.enabled || this.token?.value != token) return
        if (!replaceRejected(token)) return
        renewInBackground()
    }

    /**
     * [value] was answered `401`. True when the caller may use a new token now: [value] is no longer
     * this node's token (another thread replaced it), or it was, no replacement after a `401` ran
     * within the back-off, and it has just been dropped. False within the back-off: the token stays,
     * and the caller takes the `401` as it is.
     */
    private fun replaceRejected(value: String): Boolean = synchronized(rejectionLock) {
        val current = token
        if (current?.value != value) return true
        val now = EpistolaClock.instant()
        val previous = rejections
        if (previous != null && previous.until.isAfter(now)) {
            log.debug("Support connection: the support service refused this node's token again; not asking for another before {}", previous.until)
            return false
        }
        val failures = (previous?.failures ?: 0) + 1
        rejections = SupportBackoff(failures, now.plus(backoffDelay(failures)))
        if (token === current) token = null
        log.info("Support connection: the support service no longer accepts this node's token (401; its signing key may have changed); fetching a new one")
        true
    }

    /** A call showed this node's token accepted: the next `401` may replace a token at once. */
    private fun clearRejections() {
        if (rejections != null) rejections = null
    }

    override fun tokenAccepted(token: String) {
        if (this.token?.value == token) clearRejections()
    }

    /** Fetches a token off the caller's thread, unless one is being fetched already. Never throws. */
    private fun renewInBackground() {
        if (!renewing.compareAndSet(false, true)) return
        val clock = EpistolaClock.capture()
        try {
            renewal = Thread.ofVirtual().name("support-token-renewal").start {
                try {
                    EpistolaClock.withClock(clock) { refreshTokenIfDue() }
                } finally {
                    renewing.set(false)
                }
            }
        } catch (e: RuntimeException) {
            renewing.set(false)
            log.debug("Support connection: could not start renewing the token: {}", e.message)
        }
    }

    override fun restClient(): RestClient {
        val api = apiEndpoint() ?: currentMetadata().apiEndpoint
        rest?.takeIf { it.first == api }?.let { return it.second }
        val origin = SupportServiceMetadata.origin(api)
        val authorizing = ClientHttpRequestInterceptor { request, body, execution ->
            require(SupportServiceMetadata.origin(request.uri) == origin) {
                "Refusing to send a support request to ${request.uri}: only $origin is the support service"
            }
            val first = accessToken()
            request.headers.setBearerAuth(first)
            val response = noting { execution.execute(request, body) }
            if (response.statusCode.value() != 401) return@ClientHttpRequestInterceptor response.also { accepted(it) }
            // Within the back-off of a token refused before, the caller takes this 401 as it is.
            if (!replaceRejected(first)) return@ClientHttpRequestInterceptor response
            response.close()
            request.headers.setBearerAuth(accessToken())
            noting { execution.execute(request, body) }.also { accepted(it) }
        }
        val built = RestClient.builder()
            .baseUrl(api.toString())
            .requestFactory(supportHttp.requestFactory())
            .requestInterceptor(authorizing)
            .build()
        rest = api to built
        return built
    }

    /** An answer other than `401` or a gateway's shows the token was accepted. */
    private fun accepted(response: ClientHttpResponse) {
        val status = response.statusCode.value()
        if (status != 401 && status < 500) clearRejections()
    }

    /**
     * A data call's outcome: an I/O failure (a timeout, a refused connection, an answer over the
     * cap, a body cut off while it is read) or a `502`/`503`/`504`/`429` answer is noted as
     * unavailability, so the gates close and the next callers fail fast instead of each waiting out
     * the deadline; so is a successful answer that is not JSON (a proxy's or captive portal's HTML
     * page), which no data call can read.
     */
    private fun noting(call: () -> ClientHttpResponse): ClientHttpResponse {
        val response = try {
            call()
        } catch (e: IOException) {
            noteUnavailable("Failed calling the support service: ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
        val status = response.statusCode.value()
        // A gateway's 502/504, 503 and 429 say "not now" for every call; a 500 may be one request's
        // own bug, which must not close the gates for everything else.
        if (status == TOO_MANY_REQUESTS || status in UNAVAILABLE_STATUSES) {
            noteUnavailable("The support service answered $status")
        }
        val contentType = response.headers.contentType
        if (response.statusCode.is2xxSuccessful && contentType != null && !contentType.isCompatibleWith(MediaType.APPLICATION_JSON) && !contentType.subtype.endsWith("+json")) {
            noteUnavailable("The support service answered $status with $contentType, which cannot be read")
        }
        return NotingResponse(response) { noteUnavailable("Failed reading the support service's answer: ${it.message ?: it.javaClass.simpleName}") }
    }

    override fun checkNow() {
        checkIfIdle()
    }

    /**
     * [checkNow], unless a check or registration already runs on any node: then returns false at
     * once, and that one's result is what the page shows next.
     */
    fun checkIfIdle(): Boolean {
        if (!settings.enabled) return true
        val ran = try {
            store.tryExclusively { check(allowRegister = settings.autoRegister) } != null
        } finally {
            invalidate()
        }
        if (!ran) {
            log.debug("Support connection: a check or registration is already running; skipping this check")
            return false
        }
        // A grant or withdrawal found by the check reaches this node's token at once.
        refreshTokenIfDue()
        return true
    }

    /**
     * Registers this installation now, as an installation administrator asked: the only way to
     * register without `epistola.support.auto-register`, and the way back after an expired claim
     * code. The state is read again under the cluster lock.
     *
     * @throws SupportRegistrationNotAllowedException unless the connection is unregistered, expired or disconnected.
     */
    fun registerNow(contact: StoredSupportContact? = null) {
        if (!settings.enabled) throw SupportUnavailableException(settings.offReason)
        try {
            store.tryExclusively {
                invalidate()
                val state = status().state
                if (state !is SupportConnectionState.Unregistered && state !is SupportConnectionState.Expired && state !is SupportConnectionState.Disconnected) {
                    throw SupportRegistrationNotAllowedException("Registering is only possible while not registered, after the claim code expired or after disconnecting, not while ${state.javaClass.simpleName}")
                }
                if (state is SupportConnectionState.Expired || state is SupportConnectionState.Disconnected) {
                    store.forget()
                    invalidate()
                }
                // Only once registering is allowed and this node holds the lock: a refused request changes nothing.
                contact?.let { store.saveContact(it) }
                token = null
                localFailure = null
                log.info("Support connection: registering, as an installation administrator asked")
                check(allowRegister = true)
            } ?: throw SupportConnectionBusyException()
        } finally {
            invalidate()
        }
        refreshTokenIfDue()
    }

    /**
     * Forgets the registration and registers anew with a new key: the way back from a refused
     * registration, or from a pending one whose claim code went astray. The old registration stays
     * at the support service for staff to remove.
     *
     * The state is read again under the cluster lock: a check that held it may just have found the
     * registration activated, and an active registration is never forgotten.
     *
     * @throws SupportRegistrationNotAllowedException when the registration is not refused or pending (any more).
     */
    fun registerAgain() {
        if (!settings.enabled) throw SupportUnavailableException(settings.offReason)
        try {
            store.tryExclusively {
                invalidate()
                val state = status().state
                if (state !is SupportConnectionState.Refused && state !is SupportConnectionState.Pending && state !is SupportConnectionState.Declined) {
                    throw SupportRegistrationNotAllowedException("Registering again is only possible after a refusal or a decline, or while pending, not while ${state.javaClass.simpleName}")
                }
                store.forget()
                token = null
                localFailure = null
                invalidate()
                log.info("Support connection: forgot the registration; registering again with a new key")
                check(allowRegister = true)
            } ?: throw SupportConnectionBusyException()
        } finally {
            invalidate()
        }
    }

    /** Who Epistola contacts: what an administrator confirmed before registering, else `epistola.installation.*`. */
    fun contact(): SupportContact = supportContact(store.contact(), installation)

    // --- for SupportDisconnector ---

    /** This node's token for a call, fetched when it has none; `invalid_client` throws [SupportRefusedException]. */
    internal fun tokenForCall(): String = tokenLock.withLock { usable()?.value ?: fetchToken().value }

    /** Drops [rejected] after a `401` and fetches a new token. */
    internal fun replaceToken(rejected: String): String = invalidateToken(rejected).let { tokenLock.withLock { fetchToken().value } }

    internal fun serviceMetadata(): SupportServiceMetadata = currentMetadata()

    /** Forgets this node's token, failure and back-off once the registration is forgotten. */
    internal fun forgetNodeState() {
        token = null
        localFailure = null
        backoff = null
        invalidate()
    }

    /**
     * Keeps a token fresh on this node while the connection is pending or active, and replaces it
     * when the selection or the granted scopes changed since it was issued, or the service refused
     * one of its scopes since — a token from before a grant lacks the new scope, and the service
     * refuses it with `403 insufficient_scope`. Not while this node backs off after a failure, and not
     * while another thread fetches one. Never throws.
     */
    fun refreshTokenIfDue() {
        if (!settings.enabled) return
        val state = status().state
        if (state !is SupportConnectionState.Pending && state !is SupportConnectionState.Active) return
        val current = token
        // Granted and not paused: the service leaves a paused scope out of every token.
        val granted = (state as? SupportConnectionState.Active)?.usable(EpistolaClock.instant()).orEmpty()
        val wanted = wantedScopes(metadataCache.cached())
        val refusedSince = snapshot().refusals.values.maxOrNull()
        if (current != null &&
            current.requested == wanted &&
            current.scopes == wanted.intersect(granted) &&
            (refusedSince == null || current.seq >= refusedSince) &&
            current.expiresAt.isAfter(EpistolaClock.instant().plus(REFRESH_MARGIN))
        ) {
            return
        }
        if (backingOff() != null) return
        if (!tokenLock.tryLock()) return
        try {
            fetchToken()
        } catch (e: RuntimeException) {
            log.debug("Support connection: could not refresh the token on this node: {}", e.message)
        } finally {
            tokenLock.unlock()
        }
    }

    /** Waits for a background renewal after a `401` to finish, at most [timeout]; for tests. */
    internal fun awaitTokenRenewal(timeout: Duration = Duration.ofSeconds(10)) {
        renewal?.join(timeout)
    }

    /** Forgets this node's backoffs and last failure; for tests. */
    fun resetBackoff() {
        backoff = null
        rejections = null
        localFailure = null
        nodeClock.reset()
    }

    /** Forces the next [status] to read the stored rows; a read already under way is not kept. */
    fun invalidate() {
        synchronized(snapshotLock) {
            snapshotGeneration++
            snapshot = null
        }
    }

    // --- the check, run by one node at a time ---

    /**
     * One check, under the cluster lock. [allowRegister]: may register when there is no registration
     * (or none with this issuer), and again at once when the registration expired. A registration
     * whose key is stored but whose answer never arrived is completed either way: someone already
     * asked for it.
     */
    private fun check(allowRegister: Boolean) {
        val now = EpistolaClock.instant()
        val previous = store.status()
        if (previous?.state == StoredConnectionState.DISCONNECTED) {
            // Until an administrator registers (registerNow forgets this first), auto-register or not.
            if (readableCredentials() != null) store.forget()
            store.saveStatus(previous.copy(checkedAt = now, lastError = null))
            localFailure = null
            return
        }
        var issuer: URI? = null
        try {
            val resolved = resolveIssuer()
            issuer = resolved
            var credentials = readableCredentials()
            val registered = credentials?.clientId != null && sameIssuer(credentials.issuer, resolved)
            val resuming = credentials != null && credentials.clientId == null && sameIssuer(credentials.issuer, resolved)
            if (!registered && !resuming && !allowRegister) {
                // Not registered, and not to register on its own: the check records that it ran and
                // does not contact the support service at all.
                store.saveStatus((previous ?: StoredSupportStatus()).copy(issuer = previous?.issuer ?: resolved.toString(), checkedAt = now, lastError = null, scopesCheckedAt = previous?.scopesAt()))
                localFailure = null
                return
            }
            val serviceMetadata = refreshMetadata(resolved)
            if (credentials?.clientId == null || !sameIssuer(credentials.issuer, serviceMetadata.issuer)) {
                credentials = register(serviceMetadata, credentials, replacedExpiredAt = now.takeIf { previous?.state == StoredConnectionState.EXPIRED })
            }
            val clientId = checkNotNull(credentials.clientId)
            if (token?.clientId != clientId) token = null
            // Before asking: a refusal stored up to now is older than the grants this check learns.
            val learnedAt = store.version()
            val info = try {
                connectionInfo(serviceMetadata)
            } catch (_: SupportRegistrationGoneException) {
                registrationGone(serviceMetadata, clientId, allowRegister, now)
                return
            } catch (_: SupportRefusedException) {
                return // fetchToken stored the refusal
            }
            val state = when (info.status) {
                "ACTIVE" -> StoredConnectionState.ACTIVE
                "DECLINED" -> StoredConnectionState.DECLINED
                else -> StoredConnectionState.PENDING
            }
            val sameRegistration = previous?.takeIf { it.clientId == clientId && state == StoredConnectionState.PENDING }
            store.saveStatus(
                StoredSupportStatus(
                    issuer = serviceMetadata.issuer.toString(),
                    clientId = clientId,
                    state = state,
                    scopes = info.scopes.map { it.value }.toSet(),
                    declinedAt = info.declinedAt,
                    pause = StoredSupportPause.of(info.pause),
                    checkedAt = now,
                    scopesCheckedAt = now,
                    scopesVersion = learnedAt,
                    claimCodeExpiresAt = if (state == StoredConnectionState.PENDING) info.claimCodeExpiresAt ?: sameRegistration?.claimCodeExpiresAt else null,
                    replacedExpiredAt = sameRegistration?.replacedExpiredAt,
                ),
            )
            localFailure = null
            // The grants were just learned: they settle the refusals stored before, on every node.
            clearRefusals(learnedAt, SupportScope.entries.toSet())
            if (previous?.state != state || previous.clientId != clientId) log.info("Support connection: {} ({})", state, clientId)
        } catch (e: RuntimeException) {
            val message = e.message ?: e.javaClass.simpleName
            log.warn("Support connection check failed: {}", message)
            if (generateSequence<Throwable>(e) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).any { it is SupportNodeClockException }) {
                // This node's clock, not the installation's connection: fetchToken stored it as this
                // node's own failure and clock error, which hold only this node back. The status row
                // is left as it is (register() may just have written it): not this check's to change.
                return
            }
            // Merged into the status as it is now, not as this check read it: a refusal another node
            // stored meanwhile is kept, and when the scopes were learned stays, so a newer token still counts.
            store.noteCheckFailed(issuer?.toString(), now, message)
        }
    }

    /**
     * The never-activated registration [clientId] is gone at the service: its claim code expired, or
     * staff removed it. Forgets it, then registers again at once with a new key ([allowRegister]), or
     * records [StoredConnectionState.EXPIRED] with the old code's expiry for the page.
     */
    private fun registrationGone(serviceMetadata: SupportServiceMetadata, clientId: String, allowRegister: Boolean, now: Instant) {
        val expiredAt = store.status()?.takeIf { it.clientId == clientId }?.claimCodeExpiresAt
        store.forget()
        token = null
        localFailure = null
        invalidate()
        if (allowRegister) {
            log.info("Support connection: registration {} was never activated and the support service no longer knows it (its claim code expired, or staff removed it); registering again with a new key", clientId)
            register(serviceMetadata, null, replacedExpiredAt = now)
        } else {
            log.info("Support connection: registration {} was never activated and the support service no longer knows it (its claim code expired, or staff removed it); an installation administrator can register again under Platform → Epistola support", clientId)
            store.saveStatus(
                StoredSupportStatus(
                    issuer = serviceMetadata.issuer.toString(),
                    clientId = clientId,
                    state = StoredConnectionState.EXPIRED,
                    claimCodeExpiresAt = expiredAt,
                    checkedAt = now,
                    scopesCheckedAt = now,
                ),
            )
        }
        invalidate()
    }

    /**
     * `GET {api}/connection` with this node's token. A 401 means the token died before it expired —
     * revoked, or the service restarted — so it is dropped and a new one fetched once, which also
     * finds out whether the registration itself was refused.
     */
    private fun connectionInfo(serviceMetadata: SupportServiceMetadata): SupportConnectionInfo {
        val first = tokenLock.withLock { usable()?.value ?: fetchToken().value }
        val info = try {
            client.connection(serviceMetadata, first)
        } catch (e: SupportUnavailableException) {
            if ((e.cause as? RestClientResponseException)?.statusCode?.value() != 401) throw e
            invalidateToken(first)
            client.connection(serviceMetadata, tokenLock.withLock { usable()?.value ?: fetchToken().value })
        }
        clearRejections()
        return info
    }

    /**
     * The stored registration, or null. One that cannot be read — the encryption key it was stored
     * under is gone, or it is damaged — is forgotten, so the check registers again with a new key
     * (ADR 0050 §7, `docs/encryption.md`). Its registration stays at the service for staff to remove.
     */
    private fun readableCredentials(): StoredSupportCredentials? = try {
        store.credentials()
    } catch (e: UnreadableSupportCredentialsException) {
        log.warn("Support connection: {}; forgetting it and registering again with a new key", e.message)
        store.forget()
        token = null
        invalidate()
        null
    }

    private fun register(serviceMetadata: SupportServiceMetadata, existing: StoredSupportCredentials?, replacedExpiredAt: Instant? = null): StoredSupportCredentials {
        val key = existing?.privateJwk?.let(ECKey::parse) ?: SupportClientKeys.generate()
        val issuer = serviceMetadata.issuer.toString()
        // Stored before it is sent: a node that dies between the two registers this same key again,
        // which the service answers with the same client instead of a second registration.
        if (existing == null || !sameIssuer(existing.issuer, serviceMetadata.issuer)) {
            store.saveCredentials(StoredSupportCredentials(issuer = issuer, privateJwk = key.toJSONString()))
        }
        val registration = client.register(serviceMetadata, supportRegistrationRequest(key, installations.get().id.toString(), installation, contact(), nodeIdentity.nodeId, buildProperties?.version))
        val credentials = StoredSupportCredentials(
            issuer = issuer,
            clientId = registration.clientId,
            privateJwk = key.toJSONString(),
            claimCode = registration.claimCode,
            registeredAt = EpistolaClock.instant(),
        )
        store.saveCredentials(credentials)
        token = null
        store.saveStatus(
            StoredSupportStatus(
                issuer = issuer,
                clientId = registration.clientId,
                state = if (registration.status == "ACTIVE") StoredConnectionState.ACTIVE else StoredConnectionState.PENDING,
                checkedAt = EpistolaClock.instant(),
                scopesCheckedAt = EpistolaClock.instant(),
                claimCodeExpiresAt = registration.claimCodeExpiresAt.takeIf { registration.status != "ACTIVE" },
                replacedExpiredAt = replacedExpiredAt,
            ),
        )
        log.info("Support connection: registered as {} with {}; status {}", registration.clientId, issuer, registration.status)
        return credentials
    }

    // --- tokens, per node ---

    /** The cached token while it is valid for more than a minute and [fitsNow]. */
    private fun usable(): SupportCachedToken? = token?.takeIf { it.expiresAt.isAfter(EpistolaClock.instant().plus(USABLE_MARGIN)) && fitsNow(it) }

    /**
     * Whether [t] may still be sent with: it asked for the selection in force now — not one another
     * node replaced while it was being fetched — and it carries every selected scope the installation
     * reads as granted, unless it is younger than [REFIT_AFTER] and no check learned the grants after
     * it was asked for. A token from before a grant that another node learned would be refused
     * (`403 insufficient_scope`); a young one is kept otherwise, so a disagreement between the stored
     * grants and the service costs at most one token request per [REFIT_AFTER]. Compares only this
     * node's own instants, and store versions.
     */
    private fun fitsNow(t: SupportCachedToken): Boolean {
        val wanted = wantedScopes(metadataCache.cached())
        if (t.requested != wanted) return false
        // Granted and not paused: the service leaves a paused scope out of every token.
        val granted = (status().state as? SupportConnectionState.Active)?.usable(EpistolaClock.instant()) ?: return true
        if (t.scopes.containsAll(wanted.intersect(granted))) return true
        // A check learned the grants after this token was asked for: a new token is newer than it.
        val checkedSince = snapshot().status?.scopesVersion?.let { t.seq <= it } == true
        return !checkedSince && Duration.between(t.obtainedAt, EpistolaClock.instant()) < REFIT_AFTER
    }

    private fun fetchToken(): SupportCachedToken {
        val stored = try {
            store.credentials()
        } catch (e: UnreadableSupportCredentialsException) {
            // A lost or rotated-away key: backs off like any "not now", so the registration is not decrypted on
            // every call. The check under the cluster lock forgets it and registers again.
            noteUnavailable(e.message ?: "The stored support registration cannot be read")
            throw SupportUnavailableException("The stored support registration cannot be read", e)
        }
        val credentials = stored?.takeIf { it.clientId != null }
            ?: throw SupportUnavailableException("This installation is not registered with the support service yet")
        val clientId = checkNotNull(credentials.clientId)
        val now = EpistolaClock.instant()
        // Before asking: orders this token against the refusals of every node (SupportCachedToken.seq).
        val seq = store.version()
        var requested: Set<SupportScope> = emptySet()
        val fetched = try {
            val serviceMetadata = metadataFor(URI(credentials.issuer))
            requested = wantedScopes(serviceMetadata)
            val names = requested.map { it.value }.toSet()
            try {
                client.token(serviceMetadata, clientId, assertion(credentials, clientId, serviceMetadata, now), names)
            } catch (refused: SupportRefusedException) {
                SupportClockSkew.confirm(refused, now, nodeIdentity.nodeId, log) { at ->
                    client.token(serviceMetadata, clientId, assertion(credentials, clientId, serviceMetadata, at), names)
                }
            }
        } catch (e: SupportRefusedException) {
            token = null
            // invalid_client for a registration never seen activated is no revocation: the service
            // deletes a registration nobody activated in time. The check under the lock handles it.
            val stored = store.status()
            if (stored?.clientId != clientId || stored.state == StoredConnectionState.PENDING) {
                val expiresAt = stored?.takeIf { it.clientId == clientId }?.claimCodeExpiresAt
                if (expiresAt != null && expiresAt.isAfter(now)) {
                    // Its claim code is still valid, so the service did not purge it: a clock off by
                    // more than a minute, or staff removed it. Nothing is forgotten on that account;
                    // once the code expired, the registration counts as gone.
                    val message = "The support service refused registration $clientId (${e.message}), although its claim code is valid until $expiresAt: check this node's clock"
                    noteUnavailable(message)
                    throw SupportUnavailableException(message, e)
                }
                throw SupportRegistrationGoneException("The support service no longer knows registration $clientId, which was never activated (${e.message})")
            }
            markRefused(credentials, e.message ?: "invalid_client")
            throw e
        } catch (e: SupportNodeClockException) {
            noteUnavailable(e.message ?: "this node's clock is off")
            if (nodeClock.noteOff(e.message ?: "this node's clock is off")) invalidate()
            throw e
        } catch (e: SupportUnavailableException) {
            noteUnavailable(e.message ?: "unavailable")
            throw e
        }
        // A service older than empty-means-none grants everything to `scope=`: only what was asked counts.
        val cached = SupportCachedToken(clientId, fetched.accessToken, now.plusSeconds(fetched.expiresInSeconds), fetched.scopes.intersect(requested), now, requested, seq)
        token = cached
        localFailure = null
        backoff = null
        if (nodeClock.setRight(storedForThisNode = snapshot().nodeClocks.containsKey(nodeIdentity.nodeId))) invalidate()
        // Asked for after those refusals, and granted: they no longer hold, on any node.
        clearRefusals(seq, cached.scopes)
        // A refusal another node stored while this token was asked for is news this node must see now.
        invalidate()
        store.status()?.takeIf { it.clientId == clientId && it.state == StoredConnectionState.REFUSED }?.let { refused ->
            // Accepted again (staff restored it): no longer refused, whatever the last check said.
            store.saveStatus(refused.copy(state = if (cached.scopes.isEmpty()) StoredConnectionState.PENDING else StoredConnectionState.ACTIVE, reason = null, checkedAt = now, scopesCheckedAt = now, scopes = cached.scopes.map { it.value }.toSet()))
            invalidate()
        }
        return cached
    }

    /** Drops the shared refusals of [scopes] stored at version [upTo] or before. Never throws. */
    private fun clearRefusals(upTo: Long, scopes: Set<SupportScope>) {
        if (scopes.isEmpty()) return
        val settled = snapshot().refusals.filter { (scope, seq) -> scope in scopes && seq <= upTo }.keys
        try {
            if (store.clearRefusals(upTo, scopes.map { it.value })) {
                invalidate()
                log.info("Support connection: {} granted again; using it again on every node", settled.ifEmpty { scopes }.joinToString { it.value })
            }
        } catch (e: RuntimeException) {
            log.warn("Support connection: could not clear the stored refusals: {}", e.message)
        }
    }

    private fun assertion(credentials: StoredSupportCredentials, clientId: String, serviceMetadata: SupportServiceMetadata, at: Instant): String = SupportClientKeys.assertion(ECKey.parse(credentials.privateJwk), clientId, serviceMetadata.tokenEndpoint, at, UUIDv7.generate().toString())

    private fun invalidateToken(value: String) {
        // No lock: a request thread must not wait behind another thread's hanging token request.
        if (token?.value == value) token = null
    }

    private fun markRefused(credentials: StoredSupportCredentials, reason: String) {
        SupportRefusalLog.log(log, store.status(), credentials.clientId, reason)
        store.saveStatus(
            StoredSupportStatus(
                issuer = credentials.issuer,
                clientId = credentials.clientId,
                state = StoredConnectionState.REFUSED,
                reason = reason,
                checkedAt = EpistolaClock.instant(),
                scopesCheckedAt = EpistolaClock.instant(),
            ),
        )
        invalidate()
    }

    // --- metadata, per node ([SupportMetadataCache]) ---

    private fun resolveIssuer(): URI = settings.issuer ?: productDiscovery.endpoints().support.issuer

    /** The verified metadata of the issuer in use; fetched when this node has none. */
    private fun currentMetadata(): SupportServiceMetadata = metadataCache.cached() ?: metadataFor(store.credentials()?.issuer?.let(::URI) ?: resolveIssuer())

    private fun metadataFor(issuer: URI): SupportServiceMetadata = metadataCache.forIssuer(issuer)

    private fun refreshMetadata(issuer: URI): SupportServiceMetadata = metadataCache.refresh(issuer)

    /**
     * The stored rows. Kept while [SupportConnectionStore.version] is unchanged, which is read at most
     * once per [VERSION_CHECK_INTERVAL]: a change any node makes reaches this one within that second.
     * Read again regardless after 30 seconds, and when the version cannot be read.
     */
    private fun snapshot(): SupportRowsSnapshot {
        var version: Long? = null
        var versionRead = false
        snapshot?.takeIf { System.nanoTime() - it.readAtNanos < SNAPSHOT_TTL_NANOS }?.let { cached ->
            val now = EpistolaClock.instant()
            val checkedAt = cached.versionCheckedAt
            // A clock that went back does not hold the check off.
            if (now.isBefore(checkedAt.plus(VERSION_CHECK_INTERVAL)) && !now.isBefore(checkedAt)) return cached
            version = runCatching { store.version() }.getOrNull()
            versionRead = true
            if (!cached.partial && (version == null || version == cached.version)) {
                cached.versionCheckedAt = now
                return cached
            }
        }
        val generation = synchronized(snapshotLock) { snapshotGeneration }
        // Before the rows: a write that lands in between raises it again, so the next check reads anew.
        if (!versionRead) version = runCatching { store.version() }.getOrNull()
        val issuer = settings.issuer ?: runCatching { productDiscovery.cachedEndpoints().support.issuer }.getOrNull()
        val read = readSupportRows(store, issuer, snapshot?.services, version, log)
        // What is selected by these rows: the saved selection, or the configuration until one was saved.
        val services = read.services?.let { SupportScope.normalize(it.services) } ?: settings.configuredServices
        val previous = lastReadServices
        lastReadServices = services
        // Another node saved a selection: this node's token asked for the old one, and a newly
        // selected service would be refused with it. The next call, or the token refresh, asks again.
        if (previous != null && previous != services) token = null
        synchronized(snapshotLock) { if (snapshotGeneration == generation) snapshot = read }
        return read
    }

    private fun sameIssuer(a: String, b: URI): Boolean = a.trimEnd('/') == b.toString().trimEnd('/')

    private companion object {
        val CACHED_MARGIN: Duration = Duration.ofSeconds(30)
        val USABLE_MARGIN: Duration = Duration.ofSeconds(60)
        val REFRESH_MARGIN: Duration = Duration.ofSeconds(120)
        val SNAPSHOT_TTL_NANOS: Long = TimeUnit.SECONDS.toNanos(30)

        /** A token at least this old that lacks a granted scope is replaced ([fitsNow]). */
        val REFIT_AFTER: Duration = Duration.ofSeconds(30)
        val VERSION_CHECK_INTERVAL: Duration = Duration.ofSeconds(1)
        const val MAX_CAUSE_DEPTH = 16
        val BACKOFF_FIRST: Duration = Duration.ofSeconds(30)
        val BACKOFF_MAX: Duration = Duration.ofMinutes(5)
        const val BACKOFF_MAX_DOUBLINGS = 10
        const val TOKEN_LOCK_WAIT_MS = 250L
        const val TOO_MANY_REQUESTS = 429
        val UNAVAILABLE_STATUSES = setOf(502, 503, 504)
    }
}
