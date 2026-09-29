// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.mcp.dto.AuthoringSchemasInfo
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The shapes the write tools accept, straight from the `epistola-catalog` artifact that defines
 * them. `list_component_types` says which components exist; these say how a document holding them
 * is put together, and which style keys and units a theme or a node may set.
 */
@Component
class AuthoringSchemaMcpTools(
    private val resourceLoader: ResourceLoader,
    private val objectMapper: ObjectMapper,
) {

    /** Read once: the resources are part of the artifact and do not change while the JVM runs. */
    private val schemas: AuthoringSchemasInfo by lazy {
        AuthoringSchemasInfo(
            templateDocument = read("schemas/template-document.schema.json"),
            theme = read("schemas/theme.schema.json"),
            shared = read("schemas/template-shared.schema.json"),
            styleRegistry = read("style-registry.json"),
        )
    }

    @McpTool(
        name = "get_authoring_schemas",
        description = "JSON Schemas for what the write tools accept: `templateDocument` (the `content` of " +
            "`update_template_draft`, `create_stencil` and `update_stencil_draft`), `theme` (the styling " +
            "arguments of `create_theme` and `update_theme`), `shared` (the definitions both refer to as " +
            "`template-shared.schema.json`), and `styleRegistry` (every style key, its type and allowed " +
            "units, e.g. `fontSize` in `pt` or `sp`). Use it with `list_component_types`, which describes " +
            "the node types and their props.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getAuthoringSchemas(): AuthoringSchemasInfo = schemas

    private fun read(path: String): JsonNode = resourceLoader.getResource("classpath:META-INF/epistola-catalog/$path")
        .inputStream.use { objectMapper.readTree(it) }
}
