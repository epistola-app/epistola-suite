// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
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
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
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
class PreviewDocumentApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val objectMapper = ObjectMapper()

    @Test
    fun `preview with data that breaks the contract returns each field to fix`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        seedPublishedTemplate(tenantKey)

        val response = preview(tenantKey, apiKey, """{"customer": {"phone": 555}}""")

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/template-data-invalid")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(400)

        // The contract's ValidationProblemDetail shape: one entry per field, pointer into the request body.
        assertThat(JsonPath.read<List<String>>(body, "$.errors[*].field"))
            .containsExactly("/data/customer/name", "/data/invoiceDate", "/data/customer/phone")
        assertThat(JsonPath.read<List<Int>>(body, "$.errors[?(@.field == '/data/customer/phone')].rejectedValue"))
            .containsExactly(555)

        // The full analysis, paths as JSON Pointers into `data`.
        assertThat(JsonPath.read<List<String>>(body, "$.missingFields[*].path")).containsExactly("/customer/name", "/invoiceDate")
        assertThat(JsonPath.read<String>(body, "$.missingFields[1].schema.format")).isEqualTo("date")
        assertThat(JsonPath.read<List<String>>(body, "$.invalidFields[*].keyword")).containsExactly("type")
        assertThat(JsonPath.read<List<String>>(body, "$.missingDataSchema.required")).containsExactly("customer", "invoiceDate")
        assertThat(JsonPath.read<List<String>>(body, "$.missingDataSchema.properties.customer.required")).containsExactly("name")
    }

    @Test
    fun `a rejection with only invalid fields leaves missingDataSchema out`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        seedPublishedTemplate(tenantKey)

        val response = preview(tenantKey, apiKey, """{"customer": {"name": 1}, "invoiceDate": "2026-09-22"}""")

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = objectMapper.readTree(response.body!!)
        assertThat(body.has("missingDataSchema")).isFalse()
        assertThat(body.get("missingFields").isEmpty).isTrue()
        assertThat(body.get("invalidFields").values().map { it.get("path").asString() }).containsExactly("/customer/name")
    }

    @Test
    fun `preview with complete data still returns the pdf`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        seedPublishedTemplate(tenantKey)

        val response = preview(tenantKey, apiKey, """{"customer": {"name": "Ada"}, "invoiceDate": "2026-09-22"}""")

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PDF)
    }

    private fun preview(tenantKey: TenantKey, apiKey: String, data: String) = restTemplate.exchange(
        "/api/tenants/${tenantKey.value}/documents/preview",
        HttpMethod.POST,
        HttpEntity(
            """{"catalogId": "default", "templateId": "$TEMPLATE", "data": $data}""",
            HttpHeaders().apply {
                contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
                accept = listOf(MediaType.APPLICATION_PDF, MediaType.APPLICATION_PROBLEM_JSON)
                set(HttpHeaders.USER_AGENT, "epistola-contract/1.3.1 preview-it")
                set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
                set("X-API-Key", apiKey)
            },
        ),
        String::class.java,
    )

    private fun seedPublishedTemplate(tenantKey: TenantKey) = withMediator {
        val templateId = TemplateId(TemplateKey.of(TEMPLATE), CatalogId.default(TenantId(tenantKey)))
        CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        UpdateContractVersion(
            templateId = templateId,
            dataModel = json(
                """
                {"type": "object",
                 "properties": {
                   "customer": {"type": "object", "properties": {"name": {"type": "string"}, "phone": {"type": "string"}}, "required": ["name"]},
                   "invoiceDate": {"type": "string", "format": "date"}
                 },
                 "required": ["customer", "invoiceDate"]}
                """,
            ),
            dataExamples = listOf(
                DataExample("example-1", "Example 1", json("""{"customer": {"name": "Ada"}, "invoiceDate": "2026-09-22"}""")),
            ),
        ).execute()
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val draft = UpdateDraft(variantId = variantId, templateModel = TestTemplateBuilder.buildMinimal()).execute()!!
        PublishVersion(VersionId(draft.id, variantId)).execute()
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("pv-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Preview API Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "pv-it").execute()
        tenantKey to created.plaintextKey
    }

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    private companion object {
        const val TEMPLATE = "invoice"
    }
}
