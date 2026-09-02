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

    /**
     * One round-trip to Exchange that came back with a state, tagged by which kind it was.
     *
     * A submission is followed by however many polls its decision takes, so without [call] the
     * `submitted` outcome counts mostly polls and "how many releases did we send" has no answer.
     * Both questions are worth asking — the sum over `call` is the request rate, and
     * `call="submit"` alone is the publication rate — so the split is a tag rather than a second
     * meter.
     */
    fun submissionOutcome(status: CatalogPublicationStatus, call: RemoteCall) =
        counter(SUBMISSIONS, status.name.lowercase(), call).increment()

    /** An attempt that failed before Exchange could decide (network, timeout, 4xx/5xx). */
    fun submissionError(call: RemoteCall) = counter(SUBMISSIONS, "error", call).increment()

    fun credentialRefresh(outcome: CredentialRefreshOutcome) = Counter.builder(REFRESHES)
        .tag("outcome", outcome.tag)
        .register(meterRegistry)
        .increment()

    private fun counter(name: String, outcome: String, call: RemoteCall): Counter = Counter.builder(name)
        .tag("outcome", outcome)
        .tag("call", call.tag)
        .register(meterRegistry)

    /** Which kind of round-trip produced an outcome. */
    enum class RemoteCall(val tag: String) {
        /** Sending a release archive to Exchange for the first time. */
        SUBMIT("submit"),

        /** Asking Exchange what it has decided about a submission it already took. */
        POLL("poll"),
    }

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
