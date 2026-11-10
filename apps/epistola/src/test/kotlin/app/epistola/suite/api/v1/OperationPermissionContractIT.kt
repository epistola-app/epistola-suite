// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.AssetKey
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
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.LinkedMultiValueMap
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.util.UUID

/**
 * Every REST operation enforces the permission the contract declares for it (`x-required-permissions`,
 * "the enforced permission on each operation"), and a refusal names it (contract `auth.md`):
 *
 * - **refused when lacking it:** a key with every tenant role but the ones granting the declared
 *   permission is answered `403 forbidden` naming that permission;
 * - **enough to hold it:** a key with only the roles granting it is not refused.
 *
 * The declarations are read from the spec the server stubs ship, so a contract change is checked
 * without editing this test; an operation the contract adds fails until it is listed below.
 * Each operation runs against a tenant of its own, seeded with one resource of each kind.
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
class OperationPermissionContractIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    /** Where an operation's request goes, built from a seeded tenant. */
    private class Seeded(val tenant: TenantKey, val namespace: String) {
        val api = "/api/tenants/${tenant.value}"
        val catalog = "$api/catalogs/~/$namespace/default"
        val draft = "$catalog/releases/next"
    }

    private class Call(
        val method: HttpMethod,
        val path: (Seeded) -> String,
        val body: ((Seeded) -> String)? = null,
        val multipart: Boolean = false,
    )

    private fun get(path: (Seeded) -> String) = Call(HttpMethod.GET, path)
    private fun delete(path: (Seeded) -> String) = Call(HttpMethod.DELETE, path)
    private fun post(path: (Seeded) -> String, body: ((Seeded) -> String)? = null) = Call(HttpMethod.POST, path, body)
    private fun put(path: (Seeded) -> String, body: ((Seeded) -> String)? = null) = Call(HttpMethod.PUT, path, body)
    private fun patch(path: (Seeded) -> String, body: (Seeded) -> String = { "{}" }) = Call(HttpMethod.PATCH, path, body)

    private val stencilContent = """{"modelVersion": 1, "root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}}, "slots": {}}"""

    private val content = """{"templateModel": {"modelVersion": 1, "root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": []}}, "slots": {}}}"""

    /** How to call each operation that requires a tenant permission. */
    private val calls: Map<String, Call> = mapOf(
        "updateTenant" to patch({ it.api }, { """{"name": "Renamed"}""" }),
        "listThemes" to get({ "${it.draft}/themes" }),
        "createTheme" to post({ "${it.draft}/themes" }, { """{"key": "fresh", "name": "Fresh"}""" }),
        "getTheme" to get({ "${it.draft}/themes/brand" }),
        "updateTheme" to patch({ "${it.draft}/themes/brand" }),
        "deleteTheme" to delete({ "${it.draft}/themes/brand" }),
        "listEnvironments" to get({ "${it.api}/environments" }),
        "createEnvironment" to post({ "${it.api}/environments" }, { """{"key": "fresh", "name": "Fresh"}""" }),
        "getEnvironment" to get({ "${it.api}/environments/test" }),
        "updateEnvironment" to patch({ "${it.api}/environments/test" }),
        "deleteEnvironment" to delete({ "${it.api}/environments/test" }),
        "listEnvironmentDeployments" to get({ "${it.api}/environments/test/deployments" }),
        "deployCatalogRelease" to put({ "${it.api}/environments/test/deployments/~/${it.namespace}/default/next" }),
        "undeployCatalogRelease" to delete({ "${it.api}/environments/test/deployments/~/${it.namespace}/default/next" }),
        "listDeploymentHistory" to get({ "${it.api}/environments/test/deployment-history" }),
        "listAttributes" to get({ "${it.draft}/attributes" }),
        "createAttribute" to post({ "${it.draft}/attributes" }, { """{"key": "fresh", "name": "Fresh"}""" }),
        "getAttribute" to get({ "${it.draft}/attributes/tone" }),
        "updateAttribute" to patch({ "${it.draft}/attributes/tone" }),
        "deleteAttribute" to delete({ "${it.draft}/attributes/tone" }),
        "listCodeLists" to get({ "${it.draft}/code-lists" }),
        "createCodeList" to post({ "${it.draft}/code-lists" }, { """{"key": "fresh", "name": "Fresh", "sourceType": "inline", "entries": [{"code": "en", "label": "English"}]}""" }),
        "getCodeList" to get({ "${it.draft}/code-lists/languages" }),
        "updateCodeList" to patch({ "${it.draft}/code-lists/languages" }),
        "deleteCodeList" to delete({ "${it.draft}/code-lists/languages" }),
        "refreshCodeList" to post({ "${it.draft}/code-lists/languages/refresh" }),
        "listCodeListEntries" to get({ "${it.draft}/code-lists/languages/entries" }),
        "updateCodeListEntryHidden" to patch({ "${it.draft}/code-lists/languages/entries/nl/hidden" }, { """{"hidden": true}""" }),
        "listFonts" to get({ "${it.draft}/fonts" }),
        "getFont" to get({ "${it.draft}/fonts/missing" }),
        "listImages" to get({ "${it.draft}/images" }),
        "uploadImage" to Call(HttpMethod.POST, { "${it.draft}/images" }, multipart = true),
        "deleteImage" to delete({ "${it.draft}/images/logo" }),
        "downloadImageContent" to get({ "${it.draft}/images/logo/content" }),
        "listTemplates" to get({ "${it.draft}/templates" }),
        "createTemplate" to post({ "${it.draft}/templates" }, { """{"key": "fresh", "name": "Fresh"}""" }),
        "getTemplate" to get({ "${it.draft}/templates/letter" }),
        "updateTemplate" to patch({ "${it.draft}/templates/letter" }),
        "deleteTemplate" to delete({ "${it.draft}/templates/letter" }),
        "listCatalogTemplates" to get({ "${it.catalog}/templates" }),
        "validateTemplateData" to post({ "${it.catalog}/templates/letter/validate" }, { """{"data": {}}""" }),
        "markTemplateReady" to post({ "${it.draft}/templates/letter/mark-ready" }),
        "listVariants" to get({ "${it.draft}/templates/letter/variants" }),
        "createVariant" to post({ "${it.draft}/templates/letter/variants" }, { """{"key": "fresh", "name": "Fresh"}""" }),
        "getVariant" to get({ "${it.draft}/templates/letter/variants/initial" }),
        "updateVariant" to patch({ "${it.draft}/templates/letter/variants/initial" }),
        "deleteVariant" to delete({ "${it.draft}/templates/letter/variants/initial" }),
        "setDefaultVariant" to post({ "${it.draft}/templates/letter/variants/initial/set-default" }),
        "getVariantContent" to get({ "${it.draft}/templates/letter/variants/initial/content" }),
        "updateVariantContent" to put({ "${it.draft}/templates/letter/variants/initial/content" }, { content }),
        "markVariantReady" to post({ "${it.draft}/templates/letter/variants/initial/mark-ready" }),
        "listStencils" to get({ "${it.draft}/stencils" }),
        "createStencil" to post({ "${it.draft}/stencils" }, { """{"key": "fresh", "name": "Fresh"}""" }),
        "getStencil" to get({ "${it.draft}/stencils/header" }),
        "updateStencil" to patch({ "${it.draft}/stencils/header" }),
        "deleteStencil" to delete({ "${it.draft}/stencils/header" }),
        "getStencilContent" to get({ "${it.draft}/stencils/header/content" }),
        "updateStencilContent" to put({ "${it.draft}/stencils/header/content" }, { """{"content": $stencilContent}""" }),
        "markStencilReady" to post({ "${it.draft}/stencils/header/mark-ready" }),
        "listStencilUsage" to get({ "${it.draft}/stencils/header/usage" }),
        "listCatalogs" to get({ "${it.api}/catalogs" }),
        "listCatalogReleases" to get({ "${it.catalog}/releases" }),
        "createCatalogRelease" to post({ "${it.catalog}/releases" }, { """{"name": "Parallel work"}""" }),
        "getCatalogRelease" to get({ it.draft }),
        "updateCatalogRelease" to patch({ it.draft }),
        "deleteCatalogRelease" to delete({ it.draft }),
        "previewCatalogDraftRelease" to get({ "${it.draft}/release-preview" }),
        "releaseCatalogDraft" to post({ "${it.draft}/release" }, { """{"version": "1.1.0"}""" }),
        "previewCatalogDraftMerge" to get({ "${it.draft}/merge-preview?version=1.0.0" }),
        "getCatalogDraftMergeSides" to get({ "${it.draft}/merge-sides?version=1.0.0&entry=theme/brand" }),
        "mergeIntoCatalogDraft" to post({ "${it.draft}/merge" }, { """{"release": "1.0.0"}""" }),
        "listCatalogDependencies" to get({ "${it.draft}/dependencies" }),
        "updateCatalogDependencies" to put({ "${it.draft}/dependencies" }, { """{"dependencies": []}""" }),
        "getCatalogChanges" to get({ "${it.draft}/changes" }),
        "markCatalogReady" to post({ "${it.draft}/mark-ready" }),
        "listAvailableCatalogReleases" to get({ "${it.catalog}/available-releases" }),
        "installCatalogRelease" to post({ "${it.catalog}/available-releases/1.0.0/install" }),
        "publishReleaseToRegistry" to post({ "${it.catalog}/releases/1.0.0/publish" }, { """{"to": "exchange"}""" }),
        "listReleaseRegistries" to get({ "${it.api}/release-registries" }),
        "listRegistryReleases" to get({ "${it.api}/release-registries/exchange/catalogs/~/${it.namespace}/default/releases" }),
        "installRegistryRelease" to post({ "${it.api}/release-registries/exchange/catalogs/~/${it.namespace}/default/releases/1.0.0/install" }),
        "importCatalog" to Call(HttpMethod.POST, { "${it.api}/catalogs/import" }, multipart = true),
        "previewDocument" to post({ "${it.api}/documents/preview" }, { s -> """{"namespace": "${s.namespace}", "catalogKey": "default", "templateKey": "letter", "data": {}}""" }),
        "generateDocument" to post({ "${it.api}/documents/generate" }, { s -> """{"namespace": "${s.namespace}", "catalogKey": "default", "templateKey": "letter", "data": {}, "environmentKey": "test"}""" }),
        "generateDocumentBatch" to post({ "${it.api}/documents/generate/batch" }, { s -> """{"items": [{"namespace": "${s.namespace}", "catalogKey": "default", "templateKey": "letter", "data": {}, "environmentKey": "test"}]}""" }),
        "listGenerationJobs" to get({ "${it.api}/documents/jobs" }),
        "getGenerationJobStatus" to get({ "${it.api}/documents/jobs/${UUID.randomUUID()}" }),
        "cancelGenerationJob" to delete({ "${it.api}/documents/jobs/${UUID.randomUUID()}" }),
        "listGenerationJobItems" to get({ "${it.api}/documents/jobs/${UUID.randomUUID()}/items" }),
        "listDocuments" to get({ "${it.api}/documents" }),
        "downloadDocument" to get({ "${it.api}/documents/${UUID.randomUUID()}/content" }),
        "deleteDocument" to delete({ "${it.api}/documents/${UUID.randomUUID()}" }),
        "collectGenerationResults" to post({ "${it.api}/generation/collect" }, { "{}" }),
    )

    /**
     * Operations this test does not drive, and why. Each is covered elsewhere or enforces nothing yet.
     */
    private val notDriven: Map<String, String> = mapOf(
        "ping" to "requires authentication only",
        "listTenants" to "requires authentication only",
        "getTenant" to "requires tenant membership only",
        "createTenant" to "platform role, which an API key cannot hold (ForbiddenProblemApiIT)",
        "deleteTenant" to "platform role, which an API key cannot hold (ForbiddenProblemApiIT)",
        "registerConsumer" to "consumers are experimental and answer 501",
        "rotatePublicKey" to "consumers are experimental and answer 501",
        "listConsumers" to "consumers are experimental and answer 501",
        "getConsumer" to "consumers are experimental and answer 501",
        "updateConsumer" to "consumers are experimental and answer 501",
        "deleteConsumer" to "consumers are experimental and answer 501",
        "approveConsumer" to "consumers are experimental and answer 501",
        "rejectConsumer" to "consumers are experimental and answer 501",
        "listQualityProfiles" to "quality profiles answer 501 (NotImplementedOperationsApiIT)",
        "createQualityProfile" to "quality profiles answer 501 (NotImplementedOperationsApiIT)",
        "getQualityProfile" to "quality profiles answer 501 (NotImplementedOperationsApiIT)",
        "updateQualityProfile" to "quality profiles answer 501 (NotImplementedOperationsApiIT)",
        "deleteQualityProfile" to "quality profiles answer 501 (NotImplementedOperationsApiIT)",
        "searchTenant" to "answers 501 (NotImplementedOperationsApiIT)",
    )

    @Test
    fun `every operation enforces the permission the contract declares for it, and no other`() {
        val declared = declaredPermissions()
        assertThat(declared.keys - calls.keys - notDriven.keys).`as`("operations the contract has that this test neither drives nor excuses").isEmpty()
        assertThat(calls.keys - declared.keys).`as`("operations driven here that the contract does not declare a permission for").isEmpty()

        val findings = calls.toSortedMap().mapNotNull { (operation, call) ->
            val permissions = declared.getValue(operation)
            val granting = TenantRole.entries.filter { role -> role.permissions().any { it in permissions } }.toSet()

            val lacking = send(call, seed(TenantRole.entries.toSet() - granting))
            val holding = send(call, seed(granting))

            buildList {
                if (lacking.status != 403) {
                    add("without ${permissions.joinToString("/")}: answered ${lacking.status}, not 403 (${lacking.detail})")
                } else if (lacking.requiredPermission !in permissions.map { it.name }) {
                    add("without ${permissions.joinToString("/")}: 403 names ${lacking.requiredPermission}")
                }
                if (holding.status == 403) add("with only ${granting.joinToString()}: 403 names ${holding.requiredPermission}")
            }.takeIf { it.isNotEmpty() }?.let { "$operation: ${it.joinToString("; ")}" }
        }

        assertThat(findings).`as`("operations whose enforcement differs from the contract").isEmpty()
    }

    private class Answer(val status: Int, val requiredPermission: String?, val detail: String?)

    private fun send(call: Call, seeded: Pair<Seeded, String>): Answer {
        val (where, key) = seeded
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"), MediaType.ALL)
            set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 permission-it")
            set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
            set("Authorization", "ApiKey $key")
        }
        val entity: HttpEntity<*> = when {
            call.multipart -> {
                headers.contentType = MediaType.MULTIPART_FORM_DATA
                HttpEntity(
                    LinkedMultiValueMap<String, Any>().apply {
                        add(
                            "file",
                            object : ByteArrayResource(EpistolaImagesApiTest.PNG) {
                                override fun getFilename() = "file.png"
                            },
                        )
                    },
                    headers,
                )
            }

            call.body != null -> {
                headers.contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
                HttpEntity(call.body.invoke(where), headers)
            }

            else -> HttpEntity<Void>(headers)
        }
        val response = restTemplate.exchange(call.path(where), call.method, entity, String::class.java)
        val required = response.body?.takeIf { response.statusCode == HttpStatus.FORBIDDEN }
            ?.let { runCatching { JsonPath.read<String>(it, "$.requiredPermission") }.getOrNull() }
        val detail = response.body?.let { runCatching { JsonPath.read<String>(it, "$.detail") }.getOrNull() }
        return Answer(response.statusCode.value(), required, detail)
    }

    /** A tenant with one resource of each kind in its default catalog's draft, and a key with [roles]. */
    private fun seed(roles: Set<TenantRole>): Pair<Seeded, String> = withMediator {
        val tenantKey = TenantKey.of("perm-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenantKey, "Permission Tenant")
        val catalog = CatalogId.default(TenantId(tenantKey))
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand", catalogVersion = null).execute()
        CreateStencil(StencilId(StencilKey.of("header"), catalog), "Header", catalogVersion = null).execute()
        CreateCodeList(CodeListId(CodeListKey.of("languages"), catalog), "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands")), catalogVersion = null).execute()
        CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), catalog), "Tone", allowedValues = listOf("formal", "plain"), catalogVersion = null).execute()
        CreateDocumentTemplate(id = TemplateId(TemplateKey.of("letter"), catalog), name = "Letter", catalogVersion = null).execute()
        UploadAsset(catalog, "Logo", AssetMediaType.PNG, EpistolaImagesApiTest.PNG, 1, 1, id = AssetKey.of("logo"), catalogVersion = null).execute()
        CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenantKey)), "Test").execute()
        // A release to merge from, publish and deploy; the next version stays the draft the operations change.
        MarkCatalogReady(catalog, catalogVersion = null).execute()
        ReleaseCatalogDraft(catalog = catalog, version = "1.0.0", catalogVersion = null).execute()
        val created = createApiKey(tenantKey, "permission-it", roles = roles)
        Seeded(tenantKey, CatalogApiPaths.namespace(tenantKey)) to created.plaintextKey
    }

    /** Each operation's declared tenant permissions, from the spec the server stubs ship. */
    @Suppress("UNCHECKED_CAST")
    private fun declaredPermissions(): Map<String, Set<Permission>> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("openapi/epistola-contract.yaml"))
        val spec: Map<String, Any?> = stream.use { Yaml(LoaderOptions().apply { codePointLimit = 16 * 1024 * 1024 }).load(it) }
        return (spec["paths"] as Map<String, Map<String, Any?>>).values
            .flatMap { path -> path.values.filterIsInstance<Map<String, Any?>>() }
            .associate { operation ->
                operation["operationId"] as String to (operation["x-required-permissions"] as List<String>?).orEmpty().map(Permission::valueOf).toSet()
            }
            .filterValues { it.isNotEmpty() }
    }
}
