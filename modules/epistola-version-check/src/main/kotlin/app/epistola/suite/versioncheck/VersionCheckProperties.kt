// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "epistola.version-check")
data class VersionCheckProperties(
    val enabled: Boolean = true,
    val wellKnownUrl: String = "https://epistola.app/.well-known/epistola/releases.json",
    /** UTC hour at which the daily version-check spread window starts. */
    val dailyWindowStartHour: Int = 8,
    /**
     * Width of the daily spread window in minutes. The actual minute is derived
     * deterministically from the installation id, so each installation keeps a
     * stable run time while installs are spread across this window.
     */
    val dailyWindowMinutes: Int = 60,
    /**
     * How far ahead of a version's end-of-support date the "support ending" (deprecation) warning
     * starts showing. A still-supported install whose `supportedUntil` falls within this window of
     * the check date gets an amber heads-up to plan an upgrade; outside it, nothing extra shows.
     */
    val deprecationWarningWindow: Duration = Duration.ofDays(90),
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(5),
)
