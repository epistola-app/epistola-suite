// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("epistola.exchange")
data class ExchangeProperties(
    val enabled: Boolean = false,
    /**
     * Exchange's base URL, also used as its OAuth issuer. Blank: found through the product
     * domain's discovery document (ADR 0030), which is what production uses.
     *
     * Read through [configuredEndpoint], never directly: a key present in YAML with no value binds
     * as an empty string, not null, and an empty override that reads as "configured" would
     * silently disable discovery.
     */
    val endpoint: String? = null,
    /** Optional browser-reachable callback; otherwise derived from the initiating request. Read through [configuredCallbackUrl]. */
    val callbackUrl: String? = null,
    val pollIntervalMs: Long = 5000,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
    /** Consecutive transient failures after which a publication becomes an administrator-retryable `FAILED`. */
    val maxAttempts: Int = 10,
    /**
     * Delay before rechecking a publication that cannot proceed for a reason that is not its own
     * fault: enrollment or a namespace is missing, the tenant paused the feature, or Exchange is
     * unreachable. None of these spend the retry budget.
     */
    val setupRetryInterval: Duration = Duration.ofMinutes(1),
    /** Delay between polls of a submission Exchange has accepted but not yet decided. */
    val submittedPollInterval: Duration = Duration.ofSeconds(30),
    /**
     * How long a submission Exchange has taken but not decided is followed before it is given up on.
     *
     * Polling spends no retry budget — nothing has failed — so without this the one state that can
     * outlast every other bound would be followed for ever, holding its retained archive. Generous
     * on purpose: validation and scanning are Exchange's work and may legitimately take hours.
     */
    val submittedTimeout: Duration = Duration.ofHours(24),
    /**
     * Read timeout for downloading a release archive, kept apart from [readTimeout].
     *
     * That one is sized for an OAuth round-trip; a catalog archive is megabytes over whatever link
     * the installation has, and governing it by the same 30 seconds would fail installs that were
     * only slow. `RestClient.mutate()` cannot change a read timeout, so this needs its own client.
     */
    val archiveReadTimeout: Duration = Duration.ofMinutes(2),
    /**
     * Permits a plaintext Exchange, for a local checkout only. Credentials and archives cross this
     * connection, so it is off everywhere else — the same posture as `epistola.catalog.allow-http`.
     */
    val allowHttp: Boolean = false,
) {
    val configuredEndpoint: String? get() = endpoint.orNullIfBlank()

    val configuredCallbackUrl: String? get() = callbackUrl.orNullIfBlank()

    private fun String?.orNullIfBlank(): String? = this?.trim()?.ifEmpty { null }
}
