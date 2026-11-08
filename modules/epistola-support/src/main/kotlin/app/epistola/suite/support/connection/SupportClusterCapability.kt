// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.cluster.ClusterCapabilityContributor
import app.epistola.suite.cluster.ClusterProperties
import org.springframework.stereotype.Component

/** The cluster capability of the scheduled support work. */
object SupportCluster {
    /**
     * Required by every single-owner support task (the connection check, the feedback sweep and
     * poll, the mirror round). Advertised by a suite node while it can talk to the support service
     * as far as it knows ([SupportClusterCapability]), so a node whose clock is off hands that work
     * to the other nodes instead of owning it and skipping it (`docs/support-protocol.md`, "A node
     * whose clock is off").
     */
    const val CAPABILITY = "support"
}

/**
 * Adds [SupportCluster.CAPABILITY] to a suite node's capabilities, except while its clock is off
 * ([DefaultSupportConnection.nodeClockOff]). Rendezvous ownership of the single-owner support tasks
 * is computed over the nodes that advertise it, so the work moves within a heartbeat. A node set up
 * without the `suite` capability (a render worker) never takes on support work.
 */
@Component
class SupportClusterCapability(private val connection: DefaultSupportConnection) : ClusterCapabilityContributor {
    override fun capabilities(configured: List<String>): Set<String> = if (ClusterProperties.DEFAULT_CAPABILITY in configured && !connection.nodeClockOff()) setOf(SupportCluster.CAPABILITY) else emptySet()
}
