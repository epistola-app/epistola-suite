// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.upgrading

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Tuning for the upgrading snapshot-freshness sweep. The compatibility check needs a reasonably
 * current snapshot to test against; this sweep makes one only when the last sync is older than
 * [maxAge].
 */
@ConfigurationProperties(prefix = "epistola.support.upgrading.snapshot")
data class UpgradingSnapshotProperties(
    /** Make a new snapshot when the last sync is older than this (or none exists). */
    val maxAge: Duration = Duration.ofHours(24),
)
