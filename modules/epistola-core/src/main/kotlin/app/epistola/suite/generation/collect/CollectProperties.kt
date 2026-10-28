// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The collect feed's consumer bookkeeping (`epistola.collect`). One definition for the partition
 * assignment and the consumer status page, which both judge a node by [idleTimeoutMs].
 *
 * @property idleTimeoutMs a consumer node that has not polled for this long is considered gone.
 * @property showStaleWindowHours how long the status page keeps showing a node that is gone.
 * @property staleNodeRetentionHours how long a gone node's row is kept before it is reaped.
 * @property reaper when the stale-node reaper runs.
 */
@ConfigurationProperties(prefix = "epistola.collect")
data class CollectProperties(
    val idleTimeoutMs: Long = 60_000,
    val showStaleWindowHours: Long = 1,
    val staleNodeRetentionHours: Long = 24,
    val reaper: Reaper = Reaper(),
) {
    /**
     * @property enabled whether the reaper runs.
     * @property cron when it runs.
     */
    data class Reaper(
        val enabled: Boolean = true,
        val cron: String = "0 0 3 * * *",
    )
}
