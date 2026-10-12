// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import java.time.Instant

/** Cached state of the installation check, in `app_metadata` under [InstallationCheckService.STATUS_KEY]. */
data class InstallationCheckStatus(
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastError: String? = null,
    /** The last successful answer, already cleaned. Kept when a later attempt fails. */
    val result: InstallationCheckResult? = null,
    /** What the last attempt sent, for the "Shared with Epistola" page. */
    val lastRequest: SentRequest? = null,
)

/** The register's answer for this installation, cleaned for display (links are `https:` only). */
data class InstallationCheckResult(
    val registered: Boolean = false,
    /** `supported`, `expired` or `revoked`; null when not registered. */
    val status: String? = null,
    val route: String? = null,
    /** ISO date (`YYYY-MM-DD`). */
    val supportedUntil: String? = null,
    val supportPartyName: String? = null,
    val supportPartyUrl: String? = null,
    val supportContact: String? = null,
    /** This installation's oldest supported version (its own policy, or the general one). */
    val minVersion: String? = null,
    /** The installation's public page on epistola.app. */
    val page: String? = null,
)

/** A request this installation made to Epistola: what left the building, exactly. */
data class SentRequest(
    val at: Instant,
    val url: String,
    /** The headers set by the Suite (not the HTTP client's own, such as Host). */
    val headers: Map<String, String>,
    /** `200`, `503`, … or the error when there was no response. */
    val outcome: String,
)
