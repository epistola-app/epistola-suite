// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("epistola.exchange")
data class ExchangeProperties(
    val enabled: Boolean = false,
    val discoveryUrl: String = "https://epistola.app/.well-known/epistola/exchange.json",
    /** Local-development escape hatch; production should use the discovery document. */
    val baseUrl: String? = null,
    /** Optional browser-reachable callback; otherwise derived from the initiating request. */
    val callbackUrl: String? = null,
    val pollIntervalMs: Long = 5000,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
    /** Consecutive transient failures after which a publication becomes an administrator-retryable `FAILED`. */
    val maxAttempts: Int = 10,
    /** Delay before rechecking a publication that is waiting for enrollment or a namespace. */
    val setupRetryInterval: Duration = Duration.ofMinutes(1),
    /** Delay between polls of a submission Exchange has accepted but not yet decided. */
    val submittedPollInterval: Duration = Duration.ofSeconds(30),
)
