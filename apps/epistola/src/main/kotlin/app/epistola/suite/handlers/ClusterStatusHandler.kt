package app.epistola.suite.handlers

import app.epistola.suite.cluster.ClusterNode
import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.OffsetDateTime

@Component
class ClusterStatusHandler(
    private val registry: ClusterNodeRegistry,
    private val properties: ClusterProperties,
) {

    fun dashboard(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val report = loadReport()

        return ServerResponse.ok().page("cluster/dashboard") {
            "pageTitle" to "Cluster - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "cluster"
            "report" to report
        }
    }

    fun refresh(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val report = loadReport()

        return request.htmx {
            fragment("cluster/dashboard", "results") {
                "tenantId" to tenantId.key
                "report" to report
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/cluster") }
        }
    }

    private fun loadReport(): ClusterStatusReport {
        val currentNode = registry.heartbeat()
        val now = OffsetDateTime.now()
        val nodes = registry.allNodes().map { node ->
            val ageMs = java.time.Duration.between(node.lastSeenAt, now).toMillis().coerceAtLeast(0)
            ClusterNodeStatus(
                node = node,
                isCurrent = node.nodeId == currentNode.nodeId,
                isActive = ageMs <= properties.idleTimeoutMs,
                ageSeconds = ageMs / MILLIS_PER_SECOND,
            )
        }
        return ClusterStatusReport(
            nodes = nodes,
            heartbeatIntervalMs = properties.heartbeatIntervalMs,
            idleTimeoutMs = properties.idleTimeoutMs,
            activeCount = nodes.count { it.isActive },
            staleCount = nodes.count { !it.isActive },
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

data class ClusterStatusReport(
    val nodes: List<ClusterNodeStatus>,
    val heartbeatIntervalMs: Long,
    val idleTimeoutMs: Long,
    val activeCount: Int,
    val staleCount: Int,
) {
    val totalCount: Int = nodes.size
    val heartbeatIntervalSeconds: Long = heartbeatIntervalMs / 1_000L
    val idleTimeoutSeconds: Long = idleTimeoutMs / 1_000L
}

data class ClusterNodeStatus(
    val node: ClusterNode,
    val isCurrent: Boolean,
    val isActive: Boolean,
    val ageSeconds: Long,
) {
    val statusLabel: String = if (isActive) "active" else "stale"
    val capabilitiesLabel: String = node.capabilities.ifEmpty { listOf("suite") }.joinToString(", ")
    val versionLabel: String = node.version ?: "-"
    val ageLabel: String = when {
        ageSeconds < 1 -> "just now"
        ageSeconds == 1L -> "1s ago"
        ageSeconds < 60 -> "${ageSeconds}s ago"
        else -> "${ageSeconds / 60}m ago"
    }
}
