package app.epistola.suite.mcp.support

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.SecurityContext

/**
 * Resolve the tenant the current MCP request is operating in.
 *
 * MCP tools authenticate via per-tenant `X-API-Key`; the API-key filter
 * populates [app.epistola.suite.security.EpistolaPrincipal.currentTenantId].
 * Tools therefore never take a `tenantId` parameter — it is implicit in
 * the auth context.
 */
internal fun mcpTenantKey(): TenantKey = SecurityContext.current().currentTenantId
    ?: error("MCP request has no tenant scope: API key is not bound to a tenant")

internal fun mcpTenantId(): TenantId = TenantId(mcpTenantKey())
