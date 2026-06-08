package app.epistola.suite.support

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.HubReachability
import app.epistola.hub.proto.v1.FetchFeedbackUpdatesRequest
import app.epistola.suite.observability.NodeIdentity
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Reads the hub connectivity this node's [EpistolaHubClient] has observed (in memory, per node —
 * see [EpistolaHubClient.connectivity]). The client bean only exists when the support tier is
 * enabled, so this is resolved lazily via [ObjectProvider]; with support off it reports
 * unknown/disabled. Nothing is persisted: a request sees only the node that served it. The result
 * is a list so the UI is ready for multiple nodes once cross-node aggregation lands.
 */
@Component
class HubConnectivityService(
    private val clientProvider: ObjectProvider<EpistolaHubClient>,
    private val nodeIdentity: NodeIdentity,
) {
    /** Whether the support tier is wired (a hub client bean exists) on this node. */
    fun supportEnabled(): Boolean = clientProvider.ifAvailable != null

    /** This node's current hub connectivity. */
    fun currentNode(): NodeHubConnectivity {
        val connectivity = clientProvider.ifAvailable?.connectivity()
        return NodeHubConnectivity(
            nodeId = nodeIdentity.nodeId,
            reachability = (connectivity?.reachability ?: HubReachability.UNKNOWN).name,
            reachable = connectivity?.reachable ?: false,
            lastCheckedAt = connectivity?.lastCheckedAtEpochMillis?.let(Instant::ofEpochMilli),
            lastReachableAt = connectivity?.lastReachableAtEpochMillis?.let(Instant::ofEpochMilli),
            lastUnreachableAt = connectivity?.lastUnreachableAtEpochMillis?.let(Instant::ofEpochMilli),
            lastError = connectivity?.lastError,
        )
    }

    /** Connectivity per node. Only this node for now (no persistence); the UI renders it as a list. */
    fun allNodes(): List<NodeHubConnectivity> = listOf(currentNode())

    /**
     * Pokes a cheap installation-scoped call so the client records a fresh connectivity outcome,
     * then callers read [currentNode]. Errors are swallowed — we only want the recorded reachability,
     * not the response. No-op when the support tier is off.
     */
    fun refresh() {
        val client = clientProvider.ifAvailable ?: return
        runCatching {
            client.fetchFeedbackUpdates(FetchFeedbackUpdatesRequest.newBuilder().setAfterSeq(0).setLimit(1).build())
        }
    }
}

/** A single node's view of hub connectivity, in suite-native types (no client-library types leak out). */
data class NodeHubConnectivity(
    val nodeId: String,
    val reachability: String,
    val reachable: Boolean,
    val lastCheckedAt: Instant?,
    val lastReachableAt: Instant?,
    val lastUnreachableAt: Instant?,
    val lastError: String?,
)
