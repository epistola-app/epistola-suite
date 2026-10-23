// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

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
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class EpistolaStencilApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a stencil edit that keeps its interface leaves every instance fitting`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val seed = seedStencilUsedByPublishedTemplate(tenantKey)
        val stencilPath = stencilPath(tenantKey, seed)

        val before = get("$stencilPath/usage", apiKey)
        assertThat(before.statusCode).describedAs(before.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<Boolean>>(before.body!!, "$.items[*].fits")).containsExactly(true)

        editContent(stencilPath, apiKey, "$.content.nodes['default-text'].props.content.content[0].content[0].text", "Changed body content")
        val ready = markReady(stencilPath, apiKey)

        assertThat(JsonPath.read<List<Any>>(ready.body!!, "$.notFitting")).isEmpty()
        val after = get("$stencilPath/usage", apiKey)
        assertThat(JsonPath.read<List<Boolean>>(after.body!!, "$.items[*].fits")).containsExactly(true)
        assertThat(JsonPath.read<List<Any>>(after.body!!, "$.items[0].problems")).isEmpty()
    }

    @Test
    fun `a stencil edit that drops a filled placeholder names the instance that no longer fits, and the usage listing agrees`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val seed = seedStencilUsedByPublishedTemplate(tenantKey)
        val stencilPath = stencilPath(tenantKey, seed)

        // The template fills placeholder 'body'; the stencil now calls it 'main'.
        editContent(stencilPath, apiKey, "$.content.nodes['ph-body'].props.name", "main")
        val ready = markReady(stencilPath, apiKey)

        assertThat(JsonPath.read<List<String>>(ready.body!!, "$.notFitting[*].templateKey")).containsExactly(seed.templateKey)
        assertThat(JsonPath.read<List<String>>(ready.body!!, "$.notFitting[*].variantKey")).containsExactly(seed.variantKey)
        val after = get("$stencilPath/usage", apiKey)
        assertThat(JsonPath.read<List<Boolean>>(after.body!!, "$.items[*].fits")).containsExactly(false)
        assertThat(JsonPath.read<List<String>>(after.body!!, "$.items[0].problems")).singleElement().asString().contains("placeholder 'body'")
        assertThat(JsonPath.read<Int>(after.body!!, "$.page.total")).isEqualTo(1)
    }

    private fun stencilPath(tenantKey: TenantKey, seed: SeededUsage): String = "${withMediator { CatalogApiPaths.version(tenantKey, seed.stencilCatalogKey) }}/stencils/${seed.stencilKey}"

    /** Saves the stencil's content with the value at [path] replaced: a real change. */
    private fun editContent(stencilPath: String, apiKey: String, path: String, value: String) {
        val content = JsonPath.parse(get("$stencilPath/content", apiKey).body).set(path, value).read<Any>("$.content")
        val contentJson = com.jayway.jsonpath.Configuration.defaultConfiguration().jsonProvider().toJson(content)
        val edit = restTemplate.exchange("$stencilPath/content", HttpMethod.PUT, HttpEntity("""{"content":$contentJson}""", baseHeaders(apiKey)), String::class.java)
        assertThat(edit.statusCode).describedAs(edit.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(edit.body!!, "$.status")).isEqualTo("modified")
    }

    private fun markReady(stencilPath: String, apiKey: String) = restTemplate.exchange("$stencilPath/mark-ready", HttpMethod.POST, HttpEntity<Void>(baseHeaders(apiKey)), String::class.java).also {
        assertThat(it.statusCode).describedAs(it.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(it.body!!, "$.stencil.status")).isEqualTo("ready")
    }

    private fun get(path: String, apiKey: String) = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(baseHeaders(apiKey)), String::class.java)

    private data class SeededUsage(
        val stencilCatalogKey: String,
        val stencilKey: String,
        val templateKey: String,
        val variantKey: String,
        val templateCatalogKey: String,
    )

    /** A stencil, and a template whose variant embeds it, released: what a release contains. */
    private fun seedStencilUsedByPublishedTemplate(tenantKey: TenantKey): SeededUsage = withMediator {
        val tenantId = TenantId(tenantKey)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = "REST Upgrade Stencil", content = stencilV1()).execute()

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "REST Upgrade Template").execute().withRequiredDataExample()
        val variantKey = VariantKey.INITIAL
        UpdateVariantContent(
            variantId = VariantId(variantKey, templateId),
            templateModel = templateEmbedding(stencilId.key.value),
        ).execute()
        mediator.publishAndRelease(VariantId(variantKey, templateId))

        SeededUsage(
            stencilCatalogKey = stencilId.catalogKey.value,
            stencilKey = stencilId.key.value,
            templateKey = templateKey.value,
            variantKey = variantKey.value,
            templateCatalogKey = stencilId.catalogKey.value,
        )
    }

    private fun stencilV1(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph-body" to Node(
                id = "ph-body",
                type = "placeholder",
                slots = listOf("ph-body-fill"),
                props = mapOf("name" to "body", "kind" to "block"),
            ),
            "default-text" to Node(
                id = "default-text",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to richText("Default body content")),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-body")),
            "ph-body-fill" to Slot(id = "ph-body-fill", nodeId = "ph-body", name = "fill", children = listOf("default-text")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    private fun templateEmbedding(stencilKey: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-instance" to Node(
                id = "stencil-instance",
                type = "stencil",
                slots = listOf("stencil-children"),
                props = mapOf("stencilKey" to stencilKey, "version" to 1),
            ),
            "embedded-ph" to Node(
                id = "embedded-ph",
                type = "placeholder",
                slots = listOf("embedded-ph-fill"),
                props = mapOf("name" to "body", "kind" to "block"),
            ),
            "user-fill-text" to Node(
                id = "user-fill-text",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to richText("User-authored body")),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil-instance")),
            "stencil-children" to Slot(id = "stencil-children", nodeId = "stencil-instance", name = "children", children = listOf("embedded-ph")),
            "embedded-ph-fill" to Slot(id = "embedded-ph-fill", nodeId = "embedded-ph", name = "fill", children = listOf("user-fill-text")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.14.0 stencil-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("st-${UUID.randomUUID().toString().take(8)}")
        TestNamespaces.createTenant(tenantKey, "Stencil API Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "st-it").execute()
        tenantKey to created.plaintextKey
    }

    private fun richText(text: String): Map<String, Any?> = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf(
                "type" to "paragraph",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
            ),
        ),
    )
}
