package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mcp.dto.VersionInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.queries.versions.ListVersions
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class VersionMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_versions",
        description = "List versions for a template variant. Each variant has at most one draft " +
            "(the editable working copy) plus zero or more published versions (immutable, deployable). " +
            "Drafts are listed first, then published versions newest-first.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listVersions(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
    ): List<VersionInfo> {
        val tenantId = mcpTenantId()
        val vId = VariantId(
            VariantKey.of(variantId),
            TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantId)),
        )
        return mediator.query(ListVersions(vId)).map { VersionInfo.from(it) }
    }
}
