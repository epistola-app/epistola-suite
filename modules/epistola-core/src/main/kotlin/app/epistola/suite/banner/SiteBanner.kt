// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.banner

import com.fasterxml.jackson.annotation.JsonIgnore

/** Severity of the site banner — maps to a design-system `alert-*` variant. */
enum class SiteBannerSeverity { INFO, WARNING, ERROR }

/**
 * The installation-wide site banner shown in the app shell to every signed-in
 * user. A single instance is stored under the `site_banner` key in `app_metadata`
 * (see [SiteBannerStore]); it is operational chrome (maintenance/incident notices,
 * the demo "data may be reset" warning), not tenant-scoped content.
 */
data class SiteBanner(
    val message: String,
    val severity: SiteBannerSeverity,
    val enabled: Boolean,
) {
    /** Design-system alert modifier class for this severity (e.g. `alert-warning`). */
    @get:JsonIgnore
    val cssClass: String get() = "alert-" + severity.name.lowercase()

    /**
     * Stable token over the visible content, used by the browser to remember a
     * per-session dismissal. Editing the message or severity yields a new token,
     * so an updated banner re-appears even if the previous one was dismissed.
     * `String.hashCode()` is JLS-specified, so the token is stable across JVMs/nodes.
     */
    @get:JsonIgnore
    val contentHash: String get() = Integer.toHexString("${severity.name}|$message".hashCode())
}
