package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
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
    fun `apply stencil upgrade upgrades matching template draft instance`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val seed = seedStencilUsedByTemplateDraft(tenantKey)

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/${seed.stencilCatalogKey}/stencils/${seed.stencilKey}/upgrade",
            HttpMethod.POST,
            HttpEntity(
                """
                {
                  "templateId": "${seed.templateKey}",
                  "variantId": "${seed.variantKey}",
                  "catalogKey": "${seed.templateCatalogKey}",
                  "newVersion": 2
                }
                """.trimIndent(),
                baseHeaders(apiKey),
            ),
            String::class.java,
        )

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<Int>(body, "$.upgraded")).isEqualTo(1)
        assertThat(JsonPath.read<List<Map<String, Any>>>(body, "$.droppedFills['stencil-instance']")).hasSize(1)
        assertThat(JsonPath.read<Map<String, Any>>(body, "$.droppedBindings")).isEmpty()
        assertThat(JsonPath.read<Map<String, Any>>(body, "$.unboundRequired")).isEmpty()
    }

    private data class SeededUsage(
        val stencilCatalogKey: String,
        val stencilKey: String,
        val templateKey: String,
        val variantKey: String,
        val templateCatalogKey: String,
    )

    private fun seedStencilUsedByTemplateDraft(tenantKey: TenantKey): SeededUsage = withMediator {
        val tenantId = TenantId(tenantKey)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = "REST Upgrade Stencil", content = stencilV1()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "REST Upgrade Template").execute()
        val variantKey = VariantKey.INITIAL
        UpdateDraft(
            variantId = VariantId(variantKey, templateId),
            templateModel = templateEmbedding(stencilId.key.value),
        ).execute()

        CreateStencilVersion(stencilId = stencilId, content = stencilV2()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), stencilId)).execute()

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
                props = mapOf("content" to "Default body content"),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-body")),
            "ph-body-fill" to Slot(id = "ph-body-fill", nodeId = "ph-body", name = "fill", children = listOf("default-text")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    private fun stencilV2(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph-main" to Node(
                id = "ph-main",
                type = "placeholder",
                slots = listOf("ph-main-fill"),
                props = mapOf("name" to "main", "kind" to "block"),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-main")),
            "ph-main-fill" to Slot(id = "ph-main-fill", nodeId = "ph-main", name = "fill", children = emptyList()),
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
                props = mapOf("stencilId" to stencilKey, "version" to 1),
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
                props = mapOf("content" to "User-authored body"),
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
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.14.0 stencil-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("st-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Stencil API Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "st-it").execute()
        tenantKey to created.plaintextKey
    }
}
