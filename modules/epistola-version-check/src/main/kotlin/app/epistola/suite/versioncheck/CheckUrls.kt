// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import java.net.URI

/**
 * Refuses a check URL that could never work, so a mistyped override fails startup rather than every
 * daily check after it. Plain `http:` stays accepted: 1.3.0 took it, and tests point at local mocks.
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
