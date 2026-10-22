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
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
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
import java.util.UUID

/**
 * A released attribute's code-list binding over REST (`getAttribute`, `listAttributes` on a release).
 * The release's content names the code list's catalog by the alias its dependency list declares
 * (contract ADR 0003, ADR 0034), and the binding says that alias (`catalogKey`): the catalog it names,
 * not the attribute's own. A code list of the attribute's own catalog is bound without one.
 *
 * Catalog `letters`, in its tenant's namespace, binds an attribute to the bundled `system` catalog's
 * `iso-639-1` and another to its own `tones`, and is released as 1.0.0.
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
class ReleasedAttributeBindingApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    @Test
    fun `a released attribute's binding names the alias the release declares, and its own code list none`() {
        val (tenantKey, apiKey) = released()
        val declared = withMediator { ListReleaseDependencies(tenantKey, letters, "1.0.0").query() }
        assertThat(declared.map { it.alias }).`as`("the release declares the code list's catalog").containsExactly(SYSTEM_CATALOG_KEY.value)
        val release = withMediator { CatalogApiPaths.version(tenantKey, letters.value, "1.0.0") }

        val one = get("$release/attributes/preferred-language", apiKey)
        val listed = get("$release/attributes", apiKey)

        for (binding in listOf(JsonPath.read<Map<String, Any?>>(one, "$.codeListBinding"), JsonPath.read<List<Map<String, Any?>>>(listed, "$.items[?(@.key == 'preferred-language')].codeListBinding").single())) {
            assertThat(binding).isEqualTo(mapOf("catalogKey" to SYSTEM_CATALOG_KEY.value, "key" to "iso-639-1"))
        }
        val own = JsonPath.read<Map<String, Any?>>(get("$release/attributes/tone", apiKey), "$.codeListBinding")
        assertThat(own["catalogKey"]).`as`("the attribute's own catalog").isNull()
        assertThat(own["key"]).isEqualTo("tones")
    }

    private fun get(path: String, apiKey: String): String {
        val response = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<String>(null, headers(apiKey)), String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    /** A tenant whose `letters` binds `preferred-language` to `system`'s `iso-639-1` and `tone` to its own `tones`, released 1.0.0, and an API key. */
    private fun released(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("rab-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Released binding").execute()
        CreateCatalog(tenantKey, letters, "Letters").execute()
        CreateAttributeDefinition(
            id = AttributeId(AttributeKey.of("preferred-language"), CatalogId(letters, TenantId(tenantKey))),
            displayName = "Preferred language",
            codeListId = CodeListId(CodeListKey.of("iso-639-1"), CatalogId(SYSTEM_CATALOG_KEY, TenantId(tenantKey))),
        ).execute()
        CreateCodeList(CodeListId(CodeListKey.of("tones"), CatalogId(letters, TenantId(tenantKey))), "Tones", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("formal", "Formal"))).execute()
        CreateAttributeDefinition(
            id = AttributeId(AttributeKey.of("tone"), CatalogId(letters, TenantId(tenantKey))),
            displayName = "Tone",
            codeListId = CodeListId(CodeListKey.of("tones"), CatalogId(letters, TenantId(tenantKey))),
        ).execute()
        MarkCatalogReady(tenantKey, letters).execute()
        ReleaseCatalogDraft(tenantKey, letters, "1.0.0").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "rab-it").execute().plaintextKey
    }

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0-SNAPSHOT released-binding-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }
}
