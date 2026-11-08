// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

/**
 * Adds capabilities a node advertises while it can do the work they stand for, on top of the
 * configured ones ([ClusterProperties.normalizedCapabilities]). Asked at every heartbeat, so a node
 * that loses the ability drops the capability within a heartbeat, and the single-owner work that
 * requires it moves to the nodes that still advertise it (rendezvous ownership is computed over the
 * capable nodes only).
 *
 * Must be cheap and must not throw: it runs on the heartbeat. A contributor that throws adds nothing.
 */
fun interface ClusterCapabilityContributor {
    /** The capabilities to add now, given the [configured] ones this node advertises anyway. */
    fun capabilities(configured: List<String>): Set<String>
}
