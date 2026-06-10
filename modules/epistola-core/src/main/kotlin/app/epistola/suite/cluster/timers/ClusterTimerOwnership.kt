package app.epistola.suite.cluster.timers

import app.epistola.suite.cluster.ClusterNode
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Rendezvous-hash based ownership calculator for timer-like work.
 *
 * Ownership is used as an affinity optimization before claiming work from
 * PostgreSQL. Given the same routing key and active capable node set, every
 * node independently selects the same owner. If the active set changes, only a
 * subset of routing keys move. Correctness still comes from the row lease in
 * the registry; ownership only decides which node should attempt the claim.
 */
@Component
class ClusterTimerOwnership {

    /**
     * Returns the preferred owner for [routingKey] from [nodes], or `null` when
     * there are no eligible nodes.
     */
    fun ownerFor(routingKey: String, nodes: List<ClusterNode>): ClusterNode? = nodes.maxByOrNull {
        score(routingKey, it.nodeId)
    }

    /**
     * Convenience predicate used by pollers after they have filtered nodes by
     * required capability.
     */
    fun isOwnedBy(routingKey: String, nodeId: String, nodes: List<ClusterNode>): Boolean = ownerFor(routingKey, nodes)?.nodeId == nodeId

    private fun score(routingKey: String, nodeId: String): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$routingKey:$nodeId".toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest)
    }
}
