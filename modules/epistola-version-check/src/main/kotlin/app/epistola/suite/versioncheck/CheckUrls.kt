// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import java.net.URI

/**
 * Refuses a check endpoint an operator set that could never work, so a mistype fails startup rather
 * than every daily check after it. Plain `http:` is accepted for an operator's own setting (tests
 * point at local mocks); a discovered endpoint must be `https:` (`DiscoveredEndpoints`).
 */
internal fun requireCheckUrl(property: String, url: String, placeholder: String? = null) {
    val uri = runCatching { URI(url.replace("{id}", "id")) }.getOrNull()
    require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
        "$property must be an absolute http(s) URL, got '$url'"
    }
    require(placeholder == null || placeholder in url) {
        "$property must contain $placeholder, which is replaced by the installation id, got '$url'"
    }
}
