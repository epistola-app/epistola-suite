// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.requests.ServerLogExcerpt
import app.epistola.suite.support.requests.SupportRequest
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * A support request's server log as the installation's own pages show it: shaped by the configured
 * default (`epistola.support.privacy.telemetry`), as it was when the request was submitted. A support
 * connection is given it with its own setting instead (`RestSupportRequestSyncAdapter`).
 */
@Component
class ServerLogView(
    private val privacy: SupportPrivacy,
    private val objectMapper: ObjectMapper,
) {
    fun of(supportRequest: SupportRequest): String? = supportRequest.serverLogs?.let { ServerLogExcerpt.shaped(it, privacy.current().pseudonymised, objectMapper) }
}
