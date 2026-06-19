package app.epistola.suite.support

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.HubReachability
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
    /** The hub version reported by the most recent successful [refresh] ping on this node. */
    @Volatile
    private var lastServerVersion: String? = null

    /** Whether the support tier is wired (a hub client bean exists) on this node. */
    fun supportEnabled(): Boolean = clientProvider.ifAvailable != null

    /**
     * Backoff gate for hub operations: returns false only when the most recent probe explicitly found
     * the hub [HubReachability.UNREACHABLE]. `UNKNOWN` (not yet probed) and `REACHABLE` both return
     * true so we still attempt — the first failed attempt records `UNREACHABLE`, so subsequent checks
     * short-circuit and we stop hammering a hub that is down. No-op (true) when support is off.
     */
    fun reachable(): Boolean = clientProvider.ifAvailable?.connectivity()?.reachability != HubReachability.UNREACHABLE

    /** This node's current hub connectivity. */
    fun currentNode(): NodeHubConnectivity {
        val connectivity = clientProvider.ifAvailable?.connectivity()
        return NodeHubConnectivity(
            nodeId = nodeIdentity.nodeId,
            reachability = (connectivity?.reachability ?: HubReachability.UNKNOWN).name,
            reachable = connectivity?.reachable ?: false,
            serverVersion = lastServerVersion,
            lastCheckedAt = connectivity?.lastCheckedAtEpochMillis?.let(Instant::ofEpochMilli),
            lastReachableAt = connectivity?.lastReachableAtEpochMillis?.let(Instant::ofEpochMilli),
            lastUnreachableAt = connectivity?.lastUnreachableAtEpochMillis?.let(Instant::ofEpochMilli),
            lastError = connectivity?.lastError,
        )
    }

    /** Connectivity per node. Only this node for now (no persistence); the UI renders it as a list. */
    fun allNodes(): List<NodeHubConnectivity> = listOf(currentNode())

    /**
     * Pings the hub so the client records a fresh connectivity outcome, capturing the hub version it
     * reports. The ping is neutral (not tied to any feature), so this reflects pure hub reachability;
     * it carries the API key once registered, so this periodic heartbeat also delivers the hub's
     * entitlements-revision header (driving refreshes). Errors are swallowed — the recorded
     * reachability is what matters. No-op when the support tier is off.
     */
    fun refresh() {
        val client = clientProvider.ifAvailable ?: return
        runCatching { client.ping() }.onSuccess { lastServerVersion = it }
    }
}

/** A single node's view of hub connectivity, in suite-native types (no client-library types leak out). */
data class NodeHubConnectivity(
    val nodeId: String,
    val reachability: String,
    val reachable: Boolean,
    val serverVersion: String?,
    val lastCheckedAt: Instant?,
    val lastReachableAt: Instant?,
    val lastUnreachableAt: Instant?,
    val lastError: String?,
)
