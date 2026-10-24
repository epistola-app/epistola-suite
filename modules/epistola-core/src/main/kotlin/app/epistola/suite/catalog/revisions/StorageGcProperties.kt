// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The content collector's settings (`epistola.storage.gc.*`, ADR 0033).
 *
 * @property enabled whether the collector is scheduled; started by hand it runs regardless.
 * @property cron when it runs: nightly at 02:00 by default, a window a busy installation can move.
 * @property grace how long content nothing points at is kept before it is collected; covers content
 *   written just before its row points at it.
 * @property batchSize how many contents (or blobs) one transaction deletes.
 * @property pause how long the collector waits between batches, so a busy database is not held up.
 */
@ConfigurationProperties(prefix = "epistola.storage.gc")
data class StorageGcProperties(
    val enabled: Boolean = true,
    val cron: String = "0 0 2 * * ?",
    val grace: Duration = Duration.ofDays(1),
    val batchSize: Int = 500,
    val pause: Duration = Duration.ofMillis(200),
) {
    init {
        require(batchSize > 0) { "epistola.storage.gc.batch-size must be positive" }
        require(!grace.isNegative) { "epistola.storage.gc.grace must not be negative" }
    }
}

@Configuration
@EnableConfigurationProperties(StorageGcProperties::class)
class StorageGcConfiguration
