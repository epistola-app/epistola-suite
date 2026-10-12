// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The daily installation check: who supports this installation, and until when. It runs only when
 * `epistola.sharing.installation-id` is true. Its schedule shares the version check's daily window.
 */
@ConfigurationProperties(prefix = "epistola.installation-check")
data class InstallationCheckProperties(
    /** `{id}` is replaced by the installation id. */
    val url: String = "https://epistola.app/installation/{id}.json",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(5),
    /**
     * How long the last successful result keeps showing after later checks failed, so a single
     * failed day does not flip the support badge. After that the badge says the status could not
     * be checked.
     */
    val staleAfter: Duration = Duration.ofDays(3),
)
