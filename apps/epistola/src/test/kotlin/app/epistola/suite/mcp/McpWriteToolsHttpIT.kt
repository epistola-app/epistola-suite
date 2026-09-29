// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.api.v1.CollectSmokeSecurityConfig
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * The MCP write tools over the real transport: a real API key through the production API-key
 * filter, the Streamable HTTP handshake, and `tools/call` with the arguments an MCP client sends.
 * `McpWriteToolsIntegrationTest` covers what each tool does; this covers what only the transport
 * decides — the generated input schemas, argument binding, and how a refusal reaches the client.
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    CollectSmokeSecurityConfig::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        "epistola.generation.polling.enabled=false",
    ],
)
@ActiveProfiles("test")
class McpWriteToolsHttpIT : IntegrationTestBase() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val http = HttpClient.newHttpClient()

    @Test
    fun `tools list advertises the write tools with typed arguments`() {
        val session = McpSession(seedKey(TenantRole.entries.toSet()).second)

        val tools = session.rpc("tools/list").get("result").get("tools").values().associateBy { it.get("name").asString() }

        assertThat(tools.keys).contains(
            "create_template", "update_template", "create_variant", "update_variant", "update_template_draft",
            "update_data_contract", "create_stencil", "update_stencil", "update_stencil_draft", "create_theme",
            "update_theme", "upload_image", "upload_font", "get_authoring_schemas",
        )
        val stencilArgs = tools.getValue("create_stencil").get("inputSchema")
        assertThat(stencilArgs.get("properties").get("tags").get("type").asString()).isEqualTo("array")
        assertThat(stencilArgs.get("required").values().map { it.asString() })
            .containsExactlyInAnyOrder("catalogId", "stencilId", "name")
        assertThat(tools.getValue("create_theme").get("inputSchema").get("properties").get("spacingUnit").get("type").asString())
            .isEqualTo("number")
        assertThat(tools.getValue("create_template").get("annotations").get("readOnlyHint").asBoolean()).isFalse
    }

    @Test
    fun `an assistant builds a template over MCP and reads it back`() {
        val session = McpSession(seedKey(TenantRole.entries.toSet()).second)

        val created = session.callTool(
            "create_template",
            mapOf("catalogId" to "default", "templateId" to "welcome", "name" to "Welcome"),
        )
        assertThat(created.get("isError")?.asBoolean() ?: false).isFalse
        val variant = session.structured("list_variants", mapOf("catalogId" to "default", "templateId" to "welcome"))
            .get(0).get("id").asString()

        val draft = session.callTool(
            "update_template_draft",
            mapOf("catalogId" to "default", "templateId" to "welcome", "variantId" to variant, "content" to DOCUMENT),
        )
        assertThat(draft.get("isError")?.asBoolean() ?: false).isFalse

        val content = session.callTool(
            "get_template_content",
            mapOf("catalogId" to "default", "templateId" to "welcome", "variantId" to variant),
        )
        assertThat(content.toString()).contains("Written over MCP")
    }

    @Test
    fun `a view-only key is refused and the client is told why`() {
        val session = McpSession(seedKey(setOf(TenantRole.CONTENT_VIEWER)).second)

        val result = session.callTool(
            "create_template",
            mapOf("catalogId" to "default", "templateId" to "sneaky", "name" to "Sneaky"),
        )

        assertThat(result.get("isError").asBoolean()).isTrue
        assertThat(result.toString()).contains("TEMPLATE_EDIT")
    }

    @Test
    fun `a malformed argument comes back as a tool error naming the argument`() {
        val session = McpSession(seedKey(TenantRole.entries.toSet()).second)
        session.callTool("create_template", mapOf("catalogId" to "default", "templateId" to "t", "name" to "T"))

        val result = session.callTool(
            "update_template",
            mapOf("catalogId" to "default", "templateId" to "t", "themeId" to "x", "clearTheme" to true),
        )

        assertThat(result.get("isError").asBoolean()).isTrue
        assertThat(result.toString()).contains("clearTheme")
    }

    private fun seedKey(roles: Set<TenantRole>): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("mcpw-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "MCP Write Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "mcp-write-it", roles = roles).execute().plaintextKey
    }

    /** One Streamable HTTP session: `initialize`, then `notifications/initialized`, then requests. */
    private inner class McpSession(private val key: String) {
        private var nextId = 1
        private val sessionId: String

        init {
            val response = post(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to nextId++,
                    "method" to "initialize",
                    "params" to mapOf(
                        "protocolVersion" to "2025-03-26",
                        "capabilities" to emptyMap<String, Any>(),
                        "clientInfo" to mapOf("name" to "mcp-write-it", "version" to "1"),
                    ),
                ),
                session = null,
            )
            assertThat(response.statusCode()).isEqualTo(200)
            sessionId = response.headers().firstValue("Mcp-Session-Id").orElseThrow()
            post(mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"), sessionId)
        }

        fun rpc(method: String, params: Map<String, Any?>? = null): JsonNode {
            val body = buildMap {
                put("jsonrpc", "2.0")
                put("id", nextId++)
                put("method", method)
                if (params != null) put("params", params)
            }
            val response = post(body, sessionId)
            assertThat(response.statusCode()).isEqualTo(200)
            return objectMapper.readTree(jsonRpcPayload(response.body()))
        }

        /** The `result` of a `tools/call`: `content`, `isError` and, for typed results, `structuredContent`. */
        fun callTool(name: String, arguments: Map<String, Any?>): JsonNode = rpc("tools/call", mapOf("name" to name, "arguments" to arguments)).get("result")

        /** A tool's typed result. A list result is wrapped as `{"result": [...]}` in `structuredContent`. */
        fun structured(name: String, arguments: Map<String, Any?>): JsonNode {
            val result = callTool(name, arguments)
            val structured = result.get("structuredContent")
                ?: objectMapper.readTree(result.get("content").get(0).get("text").asString())
            return structured.get("result") ?: structured
        }

        private fun post(body: Map<String, Any?>, session: String?): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/mcp"))
                .header("Authorization", "ApiKey $key")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .apply { if (session != null) header("Mcp-Session-Id", session) }
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build()
            return http.send(request, HttpResponse.BodyHandlers.ofString())
        }

        /** The server answers either with plain JSON or with one SSE `data:` event carrying it. */
        private fun jsonRpcPayload(body: String): String = body.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: body
    }

    private companion object {
        val DOCUMENT = """
            {"modelVersion": 1, "root": "root",
             "nodes": {
               "root": {"id": "root", "type": "root", "slots": ["slot-root"]},
               "text1": {"id": "text1", "type": "text", "slots": [], "props": {"content":
                 {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Written over MCP"}]}]}}}
             },
             "slots": {"slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": ["text1"]}},
             "themeRef": {"type": "inherit"}}
        """.trimIndent()
    }
}
