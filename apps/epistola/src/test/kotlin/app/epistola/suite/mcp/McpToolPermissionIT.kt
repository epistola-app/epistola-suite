// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.api.v1.CollectSmokeSecurityConfig
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mcp.support.McpPermission
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.TenantRole
import app.epistola.suite.security.permissions
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.createApiKey
import app.epistola.suite.testing.default
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * Every MCP tool enforces the permission it declares ([McpPermission]), over the real transport with
 * a key of the roles under test:
 *
 * - **refused when lacking it:** a key with every tenant role but the ones granting the declared
 *   permission is refused, naming that permission;
 * - **enough to hold it:** a key with only the roles granting it is not refused.
 *
 * A tool without the declaration, or one missing from the calls below, fails the test; so does a row
 * of `docs/mcp.md`'s permission tables that differs from a tool's declaration.
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
class McpToolPermissionIT : IntegrationTestBase() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var context: ApplicationContext

    private val http = HttpClient.newHttpClient()

    private val document = """{"modelVersion": 1, "root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}}, "slots": {}, "themeRef": {"type": "inherit"}}"""

    private val png: String = Base64.getEncoder().encodeToString(app.epistola.suite.api.v1.EpistolaImagesApiTest.PNG)

    private val font: String by lazy {
        Base64.getEncoder().encodeToString(javaClass.classLoader.getResourceAsStream("epistola/catalogs/system/binaries/fonts/inter/inter-Regular.ttf")!!.readAllBytes())
    }

    /** The arguments each tool is called with, against the seeded tenant. */
    private val calls: Map<String, () -> Map<String, Any?>> = mapOf(
        "list_attributes" to { mapOf("catalogId" to "default") },
        "get_attribute" to { mapOf("catalogId" to "default", "attributeKey" to "tone") },
        "get_authoring_schemas" to { emptyMap() },
        "list_catalogs" to { emptyMap() },
        "create_catalog" to { mapOf("catalogId" to "fresh", "name" to "Fresh", "authority" to "local") },
        "list_namespaces" to { emptyMap() },
        "list_available_catalog_releases" to { mapOf("catalogId" to "default") },
        "list_catalog_dependencies" to { mapOf("catalogId" to "default") },
        "update_catalog_dependencies" to { mapOf("catalogId" to "default", "dependencies" to emptyList<Any>()) },
        "upgrade_catalog_dependencies" to { mapOf("catalogId" to "default") },
        "list_release_registries" to { emptyMap() },
        "list_registry_releases" to { mapOf("registry" to "exchange", "namespace" to "acme", "catalogKey" to "default") },
        "publish_release_to_registry" to { mapOf("catalogId" to "default", "version" to "1.0.0") },
        "install_release_from_registry" to { mapOf("registry" to "exchange", "namespace" to "acme", "catalogKey" to "default", "version" to "1.0.0") },
        "list_catalog_releases" to { mapOf("catalogId" to "default") },
        "start_draft" to { mapOf("catalogId" to "default", "name" to "Parallel") },
        "rename_draft" to { mapOf("catalogId" to "default", "draft" to "next", "name" to "Everyday") },
        "discard_draft" to { mapOf("catalogId" to "default", "draft" to "next") },
        "preview_release_number" to { mapOf("catalogId" to "default") },
        "release_draft" to { mapOf("catalogId" to "default", "version" to "1.1.0") },
        "preview_merge" to { mapOf("catalogId" to "default", "release" to "1.0.0") },
        "get_merge_sides" to { mapOf("catalogId" to "default", "release" to "1.0.0", "entry" to "theme/brand") },
        "merge_release" to { mapOf("catalogId" to "default", "release" to "1.0.0") },
        "list_code_lists" to { mapOf("catalogId" to "default") },
        "get_code_list" to { mapOf("catalogId" to "default", "codeListSlug" to "languages") },
        "list_code_list_entries" to { mapOf("catalogId" to "default", "codeListSlug" to "languages") },
        "list_component_types" to { emptyMap() },
        "get_component_type" to { mapOf("type" to "text") },
        "get_data_contract" to { mapOf("catalogId" to "default", "templateId" to "letter") },
        "update_data_contract" to { mapOf("catalogId" to "default", "templateId" to "letter", "dataModel" to """{"type": "object"}""", "dataExamples" to """[{"key": "example", "name": "Example", "data": {}}]""") },
        "list_environments" to { emptyMap() },
        "deploy_release" to { mapOf("environmentId" to "test", "catalogId" to "default", "version" to "1.0.0") },
        "undeploy_release" to { mapOf("environmentId" to "test", "catalogId" to "default", "version" to "1.0.0") },
        "list_fonts" to { mapOf("catalogId" to "default") },
        "upload_font" to { mapOf("catalogId" to "default", "fontId" to "brand-sans", "name" to "Brand Sans", "kind" to "sans", "faces" to """[{"weight": 400, "content": "$font"}]""") },
        "list_images" to { mapOf("catalogId" to "default") },
        "get_image" to { mapOf("catalogId" to "default", "imageId" to "logo") },
        "upload_image" to { mapOf("catalogId" to "default", "name" to "Mark", "content" to png, "mediaType" to "image/png") },
        "preview_document" to { mapOf("catalogId" to "default", "templateId" to "letter", "workingCopy" to true, "data" to "{}") },
        "analyze_template_data" to { mapOf("catalogId" to "default", "templateId" to "letter", "data" to "{}") },
        "get_catalog_changes" to { mapOf("catalogId" to "default") },
        "mark_variant_ready" to { mapOf("catalogId" to "default", "templateId" to "letter", "variantId" to "initial") },
        "mark_template_ready" to { mapOf("catalogId" to "default", "templateId" to "letter") },
        "mark_stencil_ready" to { mapOf("catalogId" to "default", "stencilId" to "header") },
        "mark_catalog_ready" to { mapOf("catalogId" to "default") },
        "list_stencils" to { mapOf("catalogId" to "default") },
        "get_stencil" to { mapOf("catalogId" to "default", "stencilId" to "header") },
        "list_stencil_usages" to { mapOf("catalogId" to "default", "stencilId" to "header") },
        "create_stencil" to { mapOf("catalogId" to "default", "stencilId" to "footer", "name" to "Footer") },
        "update_stencil" to { mapOf("catalogId" to "default", "stencilId" to "header", "name" to "Renamed") },
        "list_templates" to { mapOf("catalogId" to "default") },
        "get_template" to { mapOf("catalogId" to "default", "templateId" to "letter") },
        "list_variants" to { mapOf("catalogId" to "default", "templateId" to "letter") },
        "get_variant" to { mapOf("catalogId" to "default", "templateId" to "letter", "variantId" to "initial") },
        "get_template_content" to { mapOf("catalogId" to "default", "templateId" to "letter", "variantId" to "initial") },
        "create_template" to { mapOf("catalogId" to "default", "templateId" to "fresh", "name" to "Fresh") },
        "update_template" to { mapOf("catalogId" to "default", "templateId" to "letter", "name" to "Renamed") },
        "create_variant" to { mapOf("catalogId" to "default", "templateId" to "letter", "variantId" to "second", "title" to "Second") },
        "update_variant" to { mapOf("catalogId" to "default", "templateId" to "letter", "variantId" to "initial", "title" to "Renamed") },
        "list_themes" to { mapOf("catalogId" to "default") },
        "get_theme" to { mapOf("catalogId" to "default", "themeId" to "brand") },
        "create_theme" to { mapOf("catalogId" to "default", "themeId" to "fresh", "name" to "Fresh") },
        "update_theme" to { mapOf("catalogId" to "default", "themeId" to "brand", "name" to "Renamed") },
        "get_catalog_theme_settings" to { mapOf("catalogId" to "default") },
        "set_catalog_theme_settings" to { mapOf("catalogId" to "default", "defaultThemeKey" to "brand") },
        "apply_house_theme" to { emptyMap() },
        "update_variant_content" to { mapOf("catalogId" to "default", "templateId" to "letter", "variantId" to "initial", "content" to document) },
    )

    @Test
    fun `every tool enforces the permission it declares, and no other`() {
        val declared = declaredPermissions()
        assertThat(declared.keys - calls.keys).`as`("tools this test does not call").isEmpty()
        assertThat(calls.keys - declared.keys).`as`("calls naming no tool").isEmpty()

        val findings = declared.toSortedMap().mapNotNull { (tool, permissions) ->
            val arguments = calls.getValue(tool)
            buildList {
                if (permissions.isEmpty()) {
                    val anyKey = call(tool, arguments(), setOf(TenantRole.CONTENT_VIEWER))
                    if (anyKey != null) add("needs no permission, yet a viewer key is refused $anyKey")
                } else {
                    val granting = TenantRole.entries.filter { role -> role.permissions().any { it in permissions } }.toSet()
                    val lacking = call(tool, arguments(), TenantRole.entries.toSet() - granting)
                    if (lacking == null) {
                        add("without ${permissions.joinToString("/")}: not refused")
                    } else if (lacking !in permissions.map { it.name }) {
                        add("without ${permissions.joinToString("/")}: refused $lacking")
                    }
                    val holding = call(tool, arguments(), granting)
                    if (holding != null) add("with only ${granting.joinToString()}: refused $holding")
                }
            }.takeIf { it.isNotEmpty() }?.let { "$tool: ${it.joinToString("; ")}" }
        }

        assertThat(findings).`as`("tools whose enforcement differs from their declaration").isEmpty()
    }

    @Test
    fun `docs mcp md states each tool's declared permission`() {
        val declared = declaredPermissions()
        val documented = Files.readAllLines(Path.of("../../docs/mcp.md"))
            .mapNotNull { Regex("""^\| `(\w+)`\s*\|.*\|\s*((?:`[A-Z_]+`(?:, `[A-Z_]+`)*)|none)\s*\|\s*$""").find(it) }
            .associate { it.groupValues[1] to Regex("[A-Z_]+").findAll(it.groupValues[2]).map { m -> Permission.valueOf(m.value) }.toSet() }

        assertThat(documented.keys).`as`("every tool is in a table of docs/mcp.md, with its permission or none").containsExactlyInAnyOrderElementsOf(declared.keys)
        documented.forEach { (tool, permissions) ->
            assertThat(declared[tool]?.toSet()).`as`("docs/mcp.md for $tool").isEqualTo(permissions)
        }
    }

    /** Calls [tool] with a key of [roles] in a freshly seeded tenant: the permission it was refused, or null. */
    private fun call(tool: String, arguments: Map<String, Any?>, roles: Set<TenantRole>): String? {
        val key = seed(roles)
        val result = rpc(key, mapOf("jsonrpc" to "2.0", "id" to 2, "method" to "tools/call", "params" to mapOf("name" to tool, "arguments" to arguments))).get("result")
        if (result?.get("isError")?.asBoolean() != true) return null
        val text = result.toString()
        return Regex("""does not have permission ([A-Z_]+)""").find(text)?.groupValues?.get(1)
            ?: Regex("""does not have access to tenant""").find(text)?.let { "tenant access" }
    }

    private fun rpc(key: String, body: Map<String, Any?>): JsonNode {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/mcp"))
            .header("Authorization", "ApiKey $key")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(200)
        val payload = response.body().lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: response.body()
        return objectMapper.readTree(payload)
    }

    /** A tenant with one resource of each kind, a release 1.0.0 and an environment; a key of [roles]. */
    private fun seed(roles: Set<TenantRole>): String = withMediator {
        val tenantKey = TenantKey.of("mcpp-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenantKey, "MCP Permission Tenant")
        val catalog = CatalogId.default(TenantId(tenantKey))
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand", catalogVersion = null).execute()
        CreateStencil(StencilId(StencilKey.of("header"), catalog), "Header", catalogVersion = null).execute()
        CreateCodeList(CodeListId(CodeListKey.of("languages"), catalog), "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands")), catalogVersion = null).execute()
        CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), catalog), "Tone", allowedValues = listOf("formal", "plain"), catalogVersion = null).execute()
        CreateDocumentTemplate(id = TemplateId(TemplateKey.of("letter"), catalog), name = "Letter", catalogVersion = null).execute()
        CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenantKey)), "Test").execute()
        MarkCatalogReady(catalog, catalogVersion = null).execute()
        ReleaseCatalogDraft(catalog = catalog, version = "1.0.0", catalogVersion = null).execute()
        createApiKey(tenantKey, "mcp-permission-it", roles = roles).plaintextKey
    }

    /**
     * Each MCP tool's name and the permissions its method declares. The tool's name is read from Spring
     * AI's `@McpTool` by name: the app does not compile against Spring AI.
     */
    private fun declaredPermissions(): Map<String, List<Permission>> = context.getBeansWithAnnotation(org.springframework.stereotype.Component::class.java).values
        .flatMap { bean -> bean.javaClass.methods.toList() }
        .mapNotNull { method ->
            method.annotations.firstOrNull { it.annotationClass.qualifiedName == "org.springframework.ai.mcp.annotation.McpTool" }
                ?.let { tool -> tool.annotationClass.java.getMethod("name").invoke(tool) as String to method }
        }
        .associate { (name, method) -> name to (method.getAnnotation(McpPermission::class.java)?.value?.toList() ?: error("MCP tool $name declares no @McpPermission")) }
}
