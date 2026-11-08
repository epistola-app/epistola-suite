// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Sync configuration: whether the retry sweep and the inbound poll run, and how often. Where feedback
 * goes is the support connection (`epistola.support.*`); whether a tenant's feedback goes at all is
 * its consent (`support_sharing_choice`).
 */
@ConfigurationProperties(prefix = "epistola.support.requests.sync")
data class SupportRequestSyncProperties(
    val enabled: Boolean = false,
    val retryIntervalMs: Long = 60_000,
    /**
     * The most one sweep or poll spends talking to the support service; what is left waits for the
     * next round. Keeps a slow service from holding the round for long.
     */
    val roundBudgetMs: Long = 30_000,
    val polling: PollingProperties = PollingProperties(),
) {
    /**
     * The poll runs every [intervalMs]; after a round that found nothing it waits twice as long as
     * the last time, up to [maxIntervalMs], and a round that found changes brings it back to every
     * [intervalMs] (ADR 0053 §5: a reply shows up within about a minute).
     */
    data class PollingProperties(
        val enabled: Boolean = true,
        val intervalMs: Long = 60_000,
        val maxIntervalMs: Long = 300_000,
    )
}
