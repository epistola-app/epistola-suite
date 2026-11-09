// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
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

/**
 * #749 over HTTP, through the real API-key filter: two calling applications of one tenant each
 * collect, list and download only what they requested, and a key from before applications — an
 * application that sees every document — still sees everything (ADR 0054).
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    CollectSmokeSecurityConfig::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // The documents are really generated: the job poller exists so the test can drain the tenant
    // synchronously, and its scheduled tick is pushed out of reach so it never races that drain.
    properties = [
        "epistola.demo.enabled=false",
        "epistola.generation.polling.enabled=true",
        "epistola.generation.polling.interval-ms=3600000",
    ],
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ApplicationIsolationApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `each application collects, lists and downloads only its own documents`() {
        val tenant = tenantWithRelease()
        val (caseKey, registryKey, legacyKey) = withMediator {
            val roles = setOf(TenantRole.DOCUMENT_GENERATOR)
            val case = CreateApplication(tenant, "Case system", roles = roles).execute()
            val registry = CreateApplication(tenant, "Registry", roles = roles).execute()
            Triple(
                CreateApiKey(tenant, "case", applicationId = case.id).execute().plaintextKey,
                CreateApiKey(tenant, "registry", applicationId = registry.id).execute().plaintextKey,
                // As every key was before applications, and as the upgrade leaves each existing one.
                CreateApiKey(tenant, "legacy", roles = roles).execute().plaintextKey,
            )
        }

        val caseRequest = generate(tenant, caseKey)
        val registryRequest = generate(tenant, registryKey)
        assertThat(drainGenerationJobs(tenant)).`as`("both requests are rendered").isEqualTo(2)

        val caseResults = collect(tenant, caseKey)
        val registryResults = collect(tenant, registryKey)
        assertThat(caseResults.keys).containsExactly(caseRequest)
        assertThat(registryResults.keys).containsExactly(registryRequest)
        assertThat(collect(tenant, legacyKey).keys).`as`("a tenant-wide key still sees both").containsExactlyInAnyOrder(caseRequest, registryRequest)

        val registryDocument = registryResults.getValue(registryRequest)
        assertThat(exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/documents/$registryDocument/content", caseKey).statusCode)
            .`as`("another application's document is not found").isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/documents/$registryDocument/content", registryKey).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/documents/jobs/$registryRequest", caseKey).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(exchange(HttpMethod.DELETE, "/api/tenants/${tenant.value}/documents/$registryDocument", caseKey).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)

        val caseJobs = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/documents/jobs", caseKey).body!!
        assertThat(JsonPath.read<List<String>>(caseJobs, "$.items[*].requestId")).containsExactly(caseRequest)
        val caseDocuments = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/documents", caseKey).body!!
        assertThat(JsonPath.read<List<String>>(caseDocuments, "$.items[*].documentId")).containsExactly(caseResults.getValue(caseRequest))
    }

    /** Request ids to document ids, from one collect poll. */
    private fun collect(tenant: TenantKey, key: String): Map<String, String> {
        val response = exchange(HttpMethod.POST, "/api/tenants/${tenant.value}/generation/collect", key, "{}") {
            set(HttpHeaders.ACCEPT_ENCODING, "identity")
        }
        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        return response.body!!.trimEnd('\n').split("\n")
            .filterNot { "\"_meta\"" in it }
            .associate { JsonPath.read<String>(it, "$.requestId") to JsonPath.read<String>(it, "$.documentId") }
    }

    private fun generate(tenant: TenantKey, key: String): String {
        val namespace = withMediator { CatalogApiPaths.namespace(tenant) }
        val body = """{"namespace":"$namespace","catalogKey":"default","templateKey":"invoice","variantKey":"${VariantKey.INITIAL.value}","data":{}}"""
        val accepted = exchange(HttpMethod.POST, "/api/tenants/${tenant.value}/documents/generate", key, body)
        assertThat(accepted.statusCode).describedAs(accepted.body).isEqualTo(HttpStatus.ACCEPTED)
        return JsonPath.read(accepted.body!!, "$.requestId")
    }

    private fun tenantWithRelease(): TenantKey = withMediator {
        val tenant = TenantKey.of("apps-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenant, "Applications Tenant")
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenant))))
        CreateDocumentTemplate(variant.templateId, "Invoice", catalogVersion = null).execute().withRequiredDataExample()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Invoice"), catalogVersion = null).execute()
        mediator.publishAndRelease(variant)
        mediator.releaseNext(variant.templateId.catalogId)
        tenant
    }

    private fun exchange(method: HttpMethod, path: String, key: String, body: String? = null, extra: HttpHeaders.() -> Unit = {}) = restTemplate.exchange(path, method, HttpEntity(body, headers(key).apply(extra)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"), MediaType.parseMediaType("application/vnd.epistola.v2+ndjson"), MediaType.APPLICATION_PDF)
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 applications-it")
        set("X-EP-Node-Id", "node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }
}
