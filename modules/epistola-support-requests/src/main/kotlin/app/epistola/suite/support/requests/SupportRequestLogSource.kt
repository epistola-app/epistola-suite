// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.TenantKey

/**
 * Where a feedback item's server log comes from: the host application's log lines of the requests
 * the submitter made just before submitting, named by their W3C trace ids (`docs/support-requests.md`,
 * "The host contract"). Feedback asks for nothing else of the host's logging, so the module can
 * move to another application that provides this.
 */
fun interface SupportRequestLogSource {
    /**
     * The lines of [tenantKey] written for [traceIds], as a JSON array of lines, oldest first; null
     * when there are none. Shaped by the host's privacy rules (pseudonymised when its telemetry is).
     */
    fun excerpt(tenantKey: TenantKey, traceIds: List<String>): String?
}
