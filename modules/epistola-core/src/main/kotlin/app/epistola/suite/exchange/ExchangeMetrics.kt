// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * What this node did while talking to Exchange.
 *
 * Counters only — they are per-node work, so they carry the normal `instance` tag and sum across
 * the fleet. The installation-wide *state* of the outbox is a different question and is published
 * once per installation by [ExchangeMetricsPublisher].
 *
 * Publication is asynchronous and default-off, which means nobody watches it succeed: without a
 * failure rate and a queue age, a tenant whose credentials went stale simply stops publishing and
 * no one finds out until they look at a catalog page.
 */
@Component
class ExchangeMetrics(private val meterRegistry: MeterRegistry) {

    /** One remote submission or status poll that reached Exchange and produced a decision. */
    fun submissionOutcome(status: CatalogPublicationStatus) = counter(SUBMISSIONS, status.name.lowercase()).increment()

    /** A submission attempt that failed before Exchange could decide (network, timeout, 4xx/5xx). */
    fun submissionError() = counter(SUBMISSIONS, "error").increment()

    fun credentialRefresh(outcome: CredentialRefreshOutcome) = counter(REFRESHES, outcome.tag).increment()

    private fun counter(name: String, outcome: String): Counter = Counter.builder(name)
        .tag("outcome", outcome)
        .register(meterRegistry)

    enum class CredentialRefreshOutcome(val tag: String) {
        /** A new access token was obtained and stored. */
        RENEWED("renewed"),

        /** Exchange refused the refresh token; the tenant must reauthorize. */
        REJECTED("rejected"),

        /** The call itself failed — Exchange unreachable or erroring. */
        ERROR("error"),
    }

    private companion object {
        const val SUBMISSIONS = "epistola.exchange.publication.submissions"
        const val REFRESHES = "epistola.exchange.credential.refresh"
    }
}
