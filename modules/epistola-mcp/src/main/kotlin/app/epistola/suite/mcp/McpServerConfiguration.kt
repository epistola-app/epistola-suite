package app.epistola.suite.mcp

import org.springframework.context.annotation.Configuration

/**
 * Marker configuration for the MCP server module.
 *
 * Server settings are driven by spring.ai.mcp.server.* properties (see
 * apps/epistola/src/main/resources/application.yaml). Tools are picked up via
 * the Spring AI annotation scanner: any McpTool method on a Component bean in
 * this module's package is registered automatically.
 *
 * Tenant scoping is per-call through McpTenantContext — there is no per-server
 * tenant binding. Authentication runs in the existing apiSecurityFilterChain
 * because the MCP HTTP endpoint is mounted under /api/mcp.
 */
@Configuration(proxyBeanMethods = false)
class McpServerConfiguration
