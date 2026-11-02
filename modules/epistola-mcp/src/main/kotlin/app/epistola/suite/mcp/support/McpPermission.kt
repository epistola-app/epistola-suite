// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.support

import app.epistola.suite.security.Permission
import app.epistola.suite.security.authorizedOperation
import app.epistola.suite.security.requirePermission
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * The permissions an MCP tool requires, in the tenant its key is bound to: the one place a tool's
 * permission is declared. [mcpOperation] enforces it, `McpToolPermissionIT` drives every tool with and
 * without it, and `docs/mcp.md` is checked against it. None means any key of the tenant may call the
 * tool: it reads nothing of the tenant.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class McpPermission(vararg val value: Permission)

private val declared = ConcurrentHashMap<KFunction<*>, List<Permission>>()

/** What [tool] declares with [McpPermission]; a tool without the annotation is a mistake, refused. */
fun mcpPermissionsOf(tool: KFunction<*>): List<Permission> = declared.computeIfAbsent(tool) {
    val annotation = checkNotNull(tool.javaMethod?.getAnnotation(McpPermission::class.java)) { "MCP tool ${tool.name} declares no @McpPermission" }
    annotation.value.toList()
}

/**
 * Runs [block], the body of MCP [tool], as an operation authorized by the permissions [tool] declares
 * ([McpPermission]): each is required of the caller, refused as everywhere else, and what the tool
 * reads to do and answer it needs no permission of its own ([authorizedOperation]). Commands it
 * dispatches are checked as always. Inline, so a `return` in [block] returns from the tool.
 */
internal inline fun <T> mcpOperation(tool: KFunction<*>, block: () -> T): T {
    val permissions = mcpPermissionsOf(tool)
    if (permissions.isEmpty()) return block()
    val tenantKey = mcpTenantKey()
    permissions.drop(1).forEach { requirePermission(tenantKey, it) }
    return authorizedOperation(tenantKey, permissions.first(), block)
}
