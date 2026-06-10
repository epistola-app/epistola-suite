package app.epistola.suite.cluster

import org.springframework.stereotype.Component
import java.math.BigInteger
import java.security.MessageDigest

@Component
class ClusterTimerOwnership {

    fun ownerFor(routingKey: String, nodes: List<ClusterNode>): ClusterNode? = nodes.maxByOrNull {
        score(routingKey, it.nodeId)
    }

    fun isOwnedBy(routingKey: String, nodeId: String, nodes: List<ClusterNode>): Boolean = ownerFor(routingKey, nodes)?.nodeId == nodeId

    private fun score(routingKey: String, nodeId: String): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$routingKey:$nodeId".toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest)
    }
}
