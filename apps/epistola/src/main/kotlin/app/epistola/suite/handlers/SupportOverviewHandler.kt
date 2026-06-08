package app.epistola.suite.handlers

import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.support.HubConnectivityService
import app.epistola.suite.support.NodeHubConnectivity
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Support → Overview: the support-tier landing page. Today it shows hub connectivity per node
 * (read live from each node's client; nothing persisted, so it lists the node serving the request).
 * It is built as a per-node table, ready for multiple nodes once cross-node aggregation lands, and
 * will also host subscription/contract details in the future.
 */
@Component
class SupportOverviewHandler(
    private val connectivity: HubConnectivityService,
) {
    fun overview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        // Poke a fresh reachability check so the page reflects the current state, not a stale read.
        connectivity.refresh()
        val nodes = connectivity.allNodes().map { it.toView() }

        return ServerResponse.ok().page("support/overview") {
            "pageTitle" to "Support - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "activeNavSection" to "overview"
            "supportEnabled" to connectivity.supportEnabled()
            "nodes" to nodes
        }
    }

    private fun NodeHubConnectivity.toView(): NodeConnectivityView = NodeConnectivityView(
        nodeId = nodeId,
        reachable = reachable,
        status =
        when (reachability) {
            "REACHABLE" -> "Connected"
            "UNREACHABLE" -> "Not connected"
            else -> "Unknown"
        },
        serverVersion = serverVersion ?: "—",
        lastChecked = lastCheckedAt?.let { FORMATTER.format(it.atOffset(ZoneOffset.UTC)) } ?: "—",
        lastReachable = lastReachableAt?.let { FORMATTER.format(it.atOffset(ZoneOffset.UTC)) } ?: "—",
        error = lastError.orEmpty(),
    )

    data class NodeConnectivityView(
        val nodeId: String,
        val reachable: Boolean,
        val status: String,
        val serverVersion: String,
        val lastChecked: String,
        val lastReachable: String,
        val error: String,
    )

    private companion object {
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    }
}
