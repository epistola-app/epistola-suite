// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.exc.StreamReadException
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * An OAuth scope the support service grants (`docs/support-protocol.md`). The token's scopes are
 * the entitlement: a service is used only while the installation holds its scope.
 */
enum class SupportScope(val value: String) {
    TELEMETRY("telemetry"),
    REQUESTS("requests"),
    MIRROR("mirror"),
    USAGE("usage"),
    ;

    companion object {
        /** The wire values of every service this suite knows. */
        val NAMES: Set<String> = entries.map { it.value }.toSet()

        /**
         * Service names as configured or stored: trimmed, lower-cased, without blanks or repeats,
         * sorted. Names this suite does not know are kept, so a selection survives a version that
         * dropped or has not yet added a service.
         */
        fun normalize(names: Collection<String>): List<String> = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()

        /** The scope for a wire value; null for one this suite does not know, which is ignored. */
        fun parse(v: String): SupportScope? = entries.firstOrNull { it.value == v }

        /** Parses a space-separated OAuth `scope` string, dropping unknown values. */
        fun parseAll(scope: String?): Set<SupportScope> = scope.orEmpty().split(' ').mapNotNull { parse(it.trim()) }.toSet()
    }
}

/** Where this installation stands with the support service. */
sealed interface SupportConnectionState {
    /** `epistola.support.enabled=false`: nothing is sent, nothing is registered. */
    data object Disabled : SupportConnectionState

    /**
     * Enabled, but not (yet) registered with the support service. Without
     * `epistola.support.auto-register` it stays so until an installation administrator registers.
     */
    data object Unregistered : SupportConnectionState

    /**
     * Registered; waiting for Epistola staff to activate it for a customer with [claimCode].
     *
     * @property claimCodeExpiresAt when the support service deletes this registration if nobody activated
     *   it; null when the service did not say.
     * @property replacedExpiredAt when this registration replaced one whose claim code expired (or
     *   that staff removed) before it was activated; null otherwise.
     */
    data class Pending(
        val clientId: String,
        val claimCode: String,
        val claimCodeExpiresAt: Instant? = null,
        val replacedExpiredAt: Instant? = null,
    ) : SupportConnectionState

    /**
     * The registration was never activated, and the support service no longer knows it: its claim code
     * expired at [claimCodeExpiresAt] (null when the service never said), or staff removed it. Only
     * without `epistola.support.auto-register`; with it, the installation registers again at once.
     * An installation administrator registers again with "Register".
     */
    data class Expired(val clientId: String, val claimCodeExpiresAt: Instant?) : SupportConnectionState

    /**
     * Activated by Epistola; the services in [scopes] are granted. A service in [pause] while it is
     * in force ([SupportPause.inForceAt]) is held back by Epistola: granted, but not used.
     */
    data class Active(val clientId: String, val scopes: Set<SupportScope>, val pause: SupportPause? = null) : SupportConnectionState {
        /** The services granted and not paused now. */
        fun usable(now: Instant): Set<SupportScope> = scopes - pause?.takeIf { it.inForceAt(now) }?.scopes.orEmpty()
    }

    /** Epistola declined this installation at [at] (null when the service did not say): nothing is used. Register again to apply again. */
    data class Declined(val clientId: String, val at: Instant?) : SupportConnectionState

    /** The token endpoint refused the stored registration (`invalid_client`), e.g. it was revoked. */
    data class Refused(val clientId: String, val reason: String) : SupportConnectionState

    /**
     * An installation administrator ([by], null when unknown) disconnected at [at]: the registration
     * is forgotten, every tenant's sharing choice and the installation's preset are cleared, and
     * nothing is registered again until an administrator registers. [outcome] says how the support
     * service took it.
     */
    data class Disconnected(val at: Instant, val by: String?, val outcome: SupportDisconnectOutcome) : SupportConnectionState
}

/**
 * Services Epistola holds back for a while ([until], or until it resumes them when null), with the
 * [reason] as Epistola's staff wrote it.
 */
data class SupportPause(val scopes: Set<SupportScope>, val since: Instant, val until: Instant?, val reason: String) {
    fun inForceAt(now: Instant): Boolean = scopes.isNotEmpty() && (until == null || now.isBefore(until))
}

/** How the support service took a disconnect. */
enum class SupportDisconnectOutcome {
    /** It ended the registration: deleted the catalog mirror, keeps the feedback for its retention period. */
    CONFIRMED,

    /** It no longer accepted the registration (revoked, or never activated and removed), so there was nothing to end there. */
    ALREADY_GONE,

    /** Removed here only, as the administrator chose while the service could not be reached: the registration and data stay there until Epistola removes them. */
    LOCAL_ONLY,
}

/**
 * The connection as every node sees it.
 *
 * @property issuer the support service this installation talks to; null until it is resolved.
 * @property checkedAt when the connection was last checked with the support service.
 * @property lastError why the last contact failed; null when it succeeded.
 * @property nodeWarning a problem of nodes that does not hold back the others — their clock is off,
 *   so the service refuses their client assertions — as each such node stored it, naming itself, for
 *   the page; null when there is none. Unlike [lastError] it closes no gates.
 */
data class SupportConnectionStatus(
    val state: SupportConnectionState,
    val issuer: URI?,
    val checkedAt: Instant?,
    val lastError: String?,
    val nodeWarning: String? = null,
)

/**
 * The installation's connection to the Epistola support service: registration, tokens and the
 * addresses of its services. The bean always exists, [SupportConnectionState.Disabled] when the
 * support connection is off, so other modules inject it unconditionally.
 */
interface SupportConnection {
    /** The connection this is (ADR 0053): its row in `support_connections`, which other tables refer to. */
    val id: UUID

    /** Cached; never network I/O, cheap enough to call per request or per export. */
    fun status(): SupportConnectionStatus

    /**
     * True when [scope] may be used now: an installation administrator selected it (or the
     * configuration did, until one saved a selection), the state is [SupportConnectionState.Active],
     * Epistola granted it, the support service has not refused it since, and the service still lists
     * it in `scopes_supported` (a retired service is not used, whatever the grant says, as tokens no
     * longer ask for it). Every service gates on this before it sends anything.
     */
    fun hasScope(scope: SupportScope): Boolean

    /** Whether the installation selected [scope]: what it asks for, whatever Epistola granted. Cached; no network I/O. */
    fun isSelected(scope: SupportScope): Boolean

    /**
     * `scopes_supported` of the support service as this node last read it; null when unknown here or
     * not stated. A service missing from it is retired, and [hasScope] says no. Cached; no network I/O.
     */
    fun offeredServices(): Set<String>? = null

    /**
     * The support service answered `403 insufficient_scope` for [scope]: Epistola withdrew it. The
     * refusal is stored for the installation, so the scope reads as not granted on this node at once
     * and on every other node within a second, until a token asked for after the refusal carries it
     * again or the next check finds it granted; the service stops instead of retrying. Never throws,
     * never calls the support service.
     */
    fun scopeRefused(scope: SupportScope)

    /**
     * The support service answered a call made with [token] with `401`: it no longer accepts that
     * token, because its signing key changed or it restarted. While [token] is still this node's
     * token, it is dropped and a new one fetched in the background — at most once per back-off (30
     * seconds, doubling to five minutes, until a call shows a token accepted again), and not while
     * this node backs off after a failure — so a service that refuses every token is not asked for
     * one per call. A token already replaced is left alone. Never marks the registration refused:
     * only the token endpoint's JSON `invalid_client` does. Never blocks, never throws.
     */
    fun tokenRejected(token: String) = Unit

    /**
     * A call made with [token] was answered by the support service with anything but `401` or a
     * gateway's error: it accepts that token. While [token] is this node's token, the back-off of
     * [tokenRejected] ends, so the next `401` replaces the token at once. Callers that do not go
     * through [restClient] (the telemetry exporters) report it here. Never blocks, never throws.
     */
    fun tokenAccepted(token: String) = Unit

    /** `epistola_api_endpoint` from the verified metadata; null until it is known on this node. */
    fun apiEndpoint(): URI?

    /** `epistola_otlp_endpoint` from the verified metadata; null until it is known on this node. */
    fun otlpEndpoint(): URI?

    /**
     * `epistola_requests_endpoint` (support requests, protocol v2) from the verified metadata; null
     * until it is known on this node, or when the service names none. [restClient] calls it with
     * absolute URIs: it is on the same origin.
     */
    fun requestsEndpoint(): URI? = null

    /** Never blocks: a token valid for at least 30 more seconds, or null. */
    fun cachedAccessToken(): String?

    /**
     * A token, fetched when none is cached. Network I/O: never call it inside a database
     * transaction.
     *
     * @throws SupportUnavailableException when the support service cannot be used now.
     * @throws SupportRefusedException when the support service refused the registration.
     */
    fun accessToken(): String

    /**
     * A client for `epistola_api_endpoint`: relative URIs (`.uri("/connection")`) resolve against
     * it, every request carries a bearer token from [accessToken], a 401 replaces the token and
     * retries once (within the back-off of [tokenRejected] it is answered as it is, without asking
     * for another token), and a request to any other origin is refused. Timeouts come from the
     * properties.
     */
    fun restClient(): RestClient

    /** Registers when needed and refreshes the status. Network I/O; the scheduler and the support page call it. */
    fun checkNow()
}

/** The support service cannot be used now: unreachable, overloaded, misconfigured or not registered yet. */
open class SupportUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The support service refuses this node's client assertions because this node's clock is off
 * (`docs/support-protocol.md`, "A node whose clock is off"). Only this node cannot get a token: the
 * registration is not refused, and the other nodes carry on.
 */
class SupportNodeClockException(message: String, cause: Throwable? = null) : SupportUnavailableException(message, cause)

/**
 * The support service refused this installation's registration (`invalid_client`).
 *
 * @property serviceTime the answer's `Date` header: the service's clock when it refused, to tell a
 *   refusal of the registration from one of an assertion dated by a clock that is off; null when absent.
 */
open class SupportRefusedException(message: String, val serviceTime: Instant? = null) : RuntimeException(message)

/**
 * The support service no longer knows a registration that was never activated: its claim code expired
 * (the service deletes a PENDING registration seven days after issuing the code) or staff removed
 * it. Not a revocation: the installation registers again with a new key at its next check.
 */
class SupportRegistrationGoneException(message: String) : SupportRefusedException(message)

/**
 * True when this failure means "try again later" rather than "this item is wrong": walks the
 * causes for [SupportUnavailableException], an [IOException], a [ResourceAccessException], an answer
 * that cannot be read (not JSON, cut off), or an HTTP 5xx or 429 response. Not `507 quota_exceeded`: the installation is over a storage quota the
 * service enforces, waiting does not change that, and the item counts an attempt like a refusal.
 */
fun Throwable.isSupportUnavailable(): Boolean = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).any {
    it is SupportUnavailableException ||
        it is IOException ||
        // An answer that cannot be read (a proxy's HTML 200, cut-off JSON): not the service's word on the item.
        it is HttpMessageNotReadableException ||
        it is StreamReadException ||
        it is ResourceAccessException ||
        (it is RestClientResponseException && it.statusCode.value() != QUOTA_EXCEEDED && (it.statusCode.is5xxServerError || it.statusCode.value() == 429))
}

/**
 * True when this failure is the support service's `403 insufficient_scope` (`docs/support-protocol.md`):
 * the token does not carry the endpoint's scope, because Epistola withdrew it. Not unavailability and
 * not a fault of the item: the caller reports it with [SupportConnection.scopeRefused] and keeps the
 * work for when the scope is granted again.
 */
fun Throwable.isInsufficientScope(): Boolean = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).any {
    it is RestClientResponseException &&
        it.statusCode.value() == 403 &&
        (it.responseHeaders?.getFirst("WWW-Authenticate").orEmpty().contains("insufficient_scope") || it.responseBodyAsString.contains("insufficient_scope"))
}

/** `507 quota_exceeded`: the installation is over a storage quota (`docs/support-protocol.md`). */
private const val QUOTA_EXCEEDED = 507

/**
 * Backoff gate for background work: true while the last contact with the support service failed,
 * on this node or in the last check. Workers skip a round instead of waiting on timeouts; the next
 * successful check or token clears it.
 */
fun SupportConnection.lastContactFailed(): Boolean = status().lastError != null

private const val MAX_CAUSE_DEPTH = 16
