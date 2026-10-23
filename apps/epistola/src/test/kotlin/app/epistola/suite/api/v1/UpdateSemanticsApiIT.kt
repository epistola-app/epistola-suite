// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.themes.commands.CreateTheme
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
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Update requests follow the contract (2898309): an omitted or null member keeps its value, an empty
 * description removes it, and a flag removes a member that has no empty value, refused with 400
 * beside the member it removes.
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
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class UpdateSemanticsApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `clearDefaultTheme removes the draft's default theme, and beside a defaultTheme is refused`() {
        val (tenant, key) = seedTenantAndKey()
        withMediator { CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(CatalogKey.DEFAULT, TenantId(tenant))), name = "Brand").execute() }
        val draft = withMediator { CatalogApiPaths.version(tenant, CatalogKey.DEFAULT.value, "next") }

        val set = call(HttpMethod.PATCH, draft, key, """{"defaultTheme": {"key": "brand"}}""")
        assertThat(set.statusCode).`as`(set.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(set.body!!, "$.defaultTheme.key")).isEqualTo("brand")

        val kept = call(HttpMethod.PATCH, draft, key, """{"defaultTheme": null, "presets": null}""")
        assertThat(kept.statusCode).`as`(kept.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(kept.body!!, "$.defaultTheme.key")).`as`("null keeps it").isEqualTo("brand")

        val both = call(HttpMethod.PATCH, draft, key, """{"defaultTheme": {"key": "brand"}, "clearDefaultTheme": true}""")
        assertThat(both.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(JsonPath.read<String>(both.body!!, "$.type")).isEqualTo("https://epistola.app/errors/validation-error")

        val cleared = call(HttpMethod.PATCH, draft, key, """{"clearDefaultTheme": true}""")
        assertThat(cleared.statusCode).`as`(cleared.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Any?>(cleared.body!!, "$.defaultTheme")).isNull()
    }

    @Test
    fun `an empty description removes a stencil's and a theme's, and null keeps it`() {
        val (tenant, key) = seedTenantAndKey()
        val catalog = CatalogId(CatalogKey.DEFAULT, TenantId(tenant))
        withMediator {
            CreateStencil(id = StencilId(StencilKey.of("header"), catalog), name = "Header", description = "The letterhead").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand", description = "House style").execute()
        }
        val draft = withMediator { CatalogApiPaths.version(tenant, CatalogKey.DEFAULT.value, "next") }

        for (resource in listOf("stencils/header", "themes/brand")) {
            val kept = call(HttpMethod.PATCH, "$draft/$resource", key, """{"name": "Renamed", "description": null}""")
            assertThat(kept.statusCode).`as`(kept.body).isEqualTo(HttpStatus.OK)
            assertThat(JsonPath.read<String>(kept.body!!, "$.description")).`as`("$resource: null keeps it").isNotNull()

            val removed = call(HttpMethod.PATCH, "$draft/$resource", key, """{"description": "  "}""")
            assertThat(removed.statusCode).`as`(removed.body).isEqualTo(HttpStatus.OK)
            assertThat(JsonPath.read<Any?>(removed.body!!, "$.description")).`as`("$resource: blank removes it").isNull()
        }
    }

    @Test
    fun `clearCodeListBinding moves a bound attribute to inline values, and beside a binding is refused`() {
        val (tenant, key) = seedTenantAndKey()
        val catalog = CatalogId(CatalogKey.DEFAULT, TenantId(tenant))
        withMediator {
            val colors = CodeListId(CodeListKey.of("colors"), catalog)
            CreateCodeList(colors, "Colors", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"))).execute()
            CreateAttributeDefinition(id = AttributeId(AttributeKey.of("color"), catalog), displayName = "Color", codeListId = colors).execute()
        }
        val attribute = "${withMediator { CatalogApiPaths.version(tenant, CatalogKey.DEFAULT.value, "next") }}/attributes/color"

        val both = call(HttpMethod.PATCH, attribute, key, """{"clearCodeListBinding": true, "codeListBinding": {"key": "colors"}}""")
        assertThat(both.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(JsonPath.read<String>(both.body!!, "$.type")).isEqualTo("https://epistola.app/errors/validation-error")

        val kept = call(HttpMethod.PATCH, attribute, key, """{"displayName": "Colour", "codeListBinding": null}""")
        assertThat(JsonPath.read<String>(kept.body!!, "$.codeListBinding.key")).`as`("null keeps it").isEqualTo("colors")

        val inline = call(HttpMethod.PATCH, attribute, key, """{"clearCodeListBinding": true, "allowedValues": ["red", "blue"]}""")
        assertThat(inline.statusCode).`as`(inline.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Any?>(inline.body!!, "$.codeListBinding")).isNull()
        assertThat(JsonPath.read<List<String>>(inline.body!!, "$.allowedValues")).containsExactly("red", "blue")
    }

    private fun call(method: HttpMethod, url: String, apiKey: String, body: String? = null): ResponseEntity<String> = restTemplate.exchange(url, method, HttpEntity(body, headers(apiKey)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.5.0 update-semantics-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("upd-${UUID.randomUUID().toString().take(8)}")
        TestNamespaces.createTenant(tenantKey, "Update Semantics Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "update-semantics-it").execute().plaintextKey
    }
}
