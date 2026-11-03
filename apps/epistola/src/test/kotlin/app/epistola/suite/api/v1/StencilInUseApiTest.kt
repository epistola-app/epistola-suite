// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.testCatalog
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

/** Deleting a stencil over REST while a template uses it is refused with a 409 naming what uses it, not a 500. */
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
class StencilInUseApiTest : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `deleting a stencil a template uses is refused with 409 naming the template`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalog = testCatalog(tenantKey, CatalogKey.DEFAULT)
        withMediator {
            CreateStencil(StencilId(StencilKey.of("header"), catalog), "Header", catalogVersion = null).execute()
            val template = TemplateId(TemplateKey.of("letter"), catalog)
            CreateDocumentTemplate(id = template, name = "Letter", catalogVersion = null).execute()
            UpdateVariantContent(VariantId(VariantKey.INITIAL, template), using("header"), catalogVersion = null).execute()
        }

        val response = delete("${versionPath(tenantKey)}/stencils/header", apiKey)

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.CONFLICT)
        // The contract's generic type: a server answers only with the problem types it registers.
        assertThat(JsonPath.read<String>(response.body, "$.type")).isEqualTo("https://epistola.app/errors/conflict")
        assertThat(JsonPath.read<String>(response.body, "$.detail")).contains("'header'", "'Letter'")
        assertThat(get("${versionPath(tenantKey)}/stencils/header", apiKey).statusCode).isEqualTo(HttpStatus.OK)
    }

    private fun using(stencilKey: String) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "instance" to Node(id = "instance", type = "stencil", props = mapOf("stencilKey" to stencilKey)),
        ),
        slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("instance"))),
        themeRef = ThemeRef.Inherit,
    )

    private fun delete(path: String, apiKey: String) = restTemplate.exchange(path, HttpMethod.DELETE, HttpEntity<String>(null, headers(apiKey)), String::class.java)

    private fun get(path: String, apiKey: String) = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<String>(null, headers(apiKey)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.9.0 stencil-in-use-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    /** The default catalog's open draft. */
    private fun versionPath(tenantKey: TenantKey): String = withMediator { CatalogApiPaths.version(tenantKey) }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("siu-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenantKey, "Stencil In Use Tenant")
        val created = CreateApiKey(tenantId = tenantKey, name = "siu-it").execute()
        tenantKey to created.plaintextKey
    }
}
