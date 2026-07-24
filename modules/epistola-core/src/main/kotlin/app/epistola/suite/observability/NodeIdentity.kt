// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.observability

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress

/**
 * Stable per-process node identifier, available app-wide regardless of whether
 * the commercial support tier is enabled. This is the single source of truth
 * for "which instance am I" — it tags metrics ([instance] common tag), feeds
 * the `/ping` server info, and is reported to epistola-hub.
 *
 * Resolution order (first non-blank wins):
 *  1. `epistola.node-id` property (relaxed binding also picks up the
 *     `EPISTOLA_NODE_ID` env var)
 *  2. `EPISTOLA_NODE_ID` environment variable
 *  3. `HOSTNAME` environment variable (set in most containers)
 *  4. `POD_NAME` environment variable (Kubernetes downward API)
 *  5. the local hostname
 *  6. `"unknown"` (last resort — surfaces a misconfigured deployment loudly)
 */
@Component
class NodeIdentity(
    @param:Value("\${epistola.node-id:#{null}}")
    configuredNodeId: String?,
) {
    val nodeId: String =
        configuredNodeId?.takeIf { it.isNotBlank() }
            ?: env("EPISTOLA_NODE_ID")
            ?: env("HOSTNAME")
            ?: env("POD_NAME")
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}
