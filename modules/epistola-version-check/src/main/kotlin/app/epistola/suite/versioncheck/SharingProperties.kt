// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * What this installation shares with Epistola — "Data shared with Epistola" v1
 * (docs/outbound-traffic.md). Everything is shared by default; an operator turns off what they do
 * not want to share. The checks follow from it: the installation check runs only when
 * [installationId] is shared. Exchange and the hub connection are explicit integrations with their
 * own settings and are not governed here.
 */
@ConfigurationProperties(prefix = "epistola.sharing")
data class SharingProperties(
    /** Share the installation id: enables the installation check (support status, counting). */
    val installationId: Boolean = true,
    /** Send the running version along with the installation check. */
    val suiteVersion: Boolean = true,
    /**
     * Reserved for anonymous usage counters. Nothing is sent yet; the release that introduces the
     * counters adds them to the shared-data list and announces it.
     */
    val usageStatistics: Boolean = true,
)
