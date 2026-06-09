package app.epistola.suite.support.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.support.EntitlementEffect
import app.epistola.suite.support.EntitlementSyncService
import app.epistola.suite.support.HubConnectivityService
import app.epistola.suite.support.NodeHubConnectivity
import app.epistola.suite.support.StoredEntitlement
import app.epistola.suite.support.SupportEntitlementService
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.beans.factory.ObjectProvider
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
    private val entitlements: ObjectProvider<SupportEntitlementService>,
    private val entitlementSync: ObjectProvider<EntitlementSyncService>,
) {
    fun overview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        // Poke a fresh reachability check so the page reflects the current state, not a stale read.
        connectivity.refresh()
        val nodes = connectivity.allNodes().map { it.toView() }

        val entitlementService = entitlements.ifAvailable
        val entitlementRows = entitlementService?.entries()?.map { it.toView() } ?: emptyList()
        val entitlementsFetchedAt =
            entitlementService?.lastFetchedAt()?.let { FORMATTER.format(it.atOffset(ZoneOffset.UTC)) }

        return ServerResponse.ok().page("support/overview") {
            "pageTitle" to "Support - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "activeNavSection" to "overview"
            "supportEnabled" to connectivity.supportEnabled()
            "nodes" to nodes
            "entitlements" to entitlementRows
            "entitlementsFetchedAt" to entitlementsFetchedAt
        }
    }

    /** Forces an immediate entitlement refresh from the hub, then returns to the overview. */
    fun refreshEntitlements(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        // No-op when the support tier is off (no sync bean) — the page just reloads.
        entitlementSync.ifAvailable?.refresh()
        return ServerResponse.ok().header("HX-Redirect", "/tenants/${tenantId.key.value}/support").build()
    }

    private fun StoredEntitlement.toView(): EntitlementRowView = EntitlementRowView(
        feature = featureKey,
        description = DESCRIPTIONS_BY_KEY[featureKey].orEmpty(),
        scope = tenant ?: "All tenants",
        effect = if (effect == EntitlementEffect.ALLOW) "Allowed" else "Denied",
        allowed = effect == EntitlementEffect.ALLOW,
        expires = expiresAt?.let { FORMATTER.format(it.atOffset(ZoneOffset.UTC)) } ?: "Never",
    )

    data class EntitlementRowView(
        val feature: String,
        val description: String,
        val scope: String,
        val effect: String,
        val allowed: Boolean,
        val expires: String,
    )

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

        /** Human descriptions keyed by the wire feature key, for the entitlements table. */
        val DESCRIPTIONS_BY_KEY: Map<String, String> =
            KnownFeatures.descriptions.entries.associate { it.key.value to it.value }
    }
}
