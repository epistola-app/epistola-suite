package app.epistola.suite.mcp.tools

import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mcp.dto.AttributeInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Read-only discovery tools for variant-attribute definitions. AI clients
 * use these to learn (a) which keys exist for a tenant, (b) which catalog
 * each lives in, (c) whether it's a free-format / inline / code-list-bound
 * constraint, and (d) whether the definition is read-only (system catalog).
 */
@Component
class AttributeMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_attributes",
        description = "List variant attribute definitions in the current tenant. " +
            "Optionally narrow by catalog. Returns the full definition: " +
            "constraint kind (allowedValues / codeListBinding / free), " +
            "catalog origin, and readOnly flag for SUBSCRIBED-catalog attributes.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listAttributes(
        @McpToolParam(description = "Catalog slug to filter by. Omit to list all.", required = false)
        catalogId: String?,
    ): List<AttributeInfo> {
        val tenantId = TenantId(mcpTenantKey())
        val filter = catalogId?.takeIf { it.isNotBlank() }?.let { CatalogKey.of(it) }
        return mediator.query(ListAttributeDefinitions(tenantId = tenantId, catalogKey = filter))
            .map { AttributeInfo.from(it) }
    }

    @McpTool(
        name = "get_attribute",
        description = "Fetch a single attribute definition by `catalogId` + `attributeKey`. " +
            "Same shape as `list_attributes`. Returns null when the attribute does not exist.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getAttribute(
        @McpToolParam(description = "Catalog the attribute lives in.")
        catalogId: String,
        @McpToolParam(description = "Attribute slug (e.g. `locale`).")
        attributeKey: String,
    ): AttributeInfo? {
        val tenantId = TenantId(mcpTenantKey())
        val id = AttributeId(AttributeKey.of(attributeKey), CatalogId(CatalogKey.of(catalogId), tenantId))
        return mediator.query(GetAttributeDefinition(id = id))?.let { AttributeInfo.from(it) }
    }
}
