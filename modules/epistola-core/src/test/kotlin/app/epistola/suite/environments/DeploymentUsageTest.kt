// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.commands.UndeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * How much each deployed version is used: requests through an environment are counted against the
 * version they render, so the people running it can see which deployed versions nobody uses any
 * more. Requests naming no environment are not counted; undeploying resets the count.
 */
class DeploymentUsageTest : IntegrationTestBase() {

    @Autowired private lateinit var usage: DeploymentUsage

    private val objectMapper = ObjectMapper()

    @Test
    fun `requests through an environment are counted against the version they render`() {
        val (tenant, variant) = withMediator {
            val tenant = createTenant("Deployment usage").id
            val templateId = TemplateId(TemplateKey.of("letter"), CatalogId.default(TenantId(tenant)))
            CreateDocumentTemplate(templateId, "Letter").execute()
            val variant = VariantId(VariantKey.of("standard"), templateId)
            CreateVariant(id = variant, title = "Standard", description = null, attributes = emptyMap()).execute()
            UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter")).execute()
            mediator.publishAndRelease(variant)
            UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter 2")).execute()
            mediator.publishAndRelease(variant)
            val test = EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant))
            CreateEnvironment(test, "Test").execute()
            DeployCatalogRelease(test, testCatalog(test.tenantKey, CatalogKey.DEFAULT), "1.0.0").execute()
            DeployCatalogRelease(test, testCatalog(test.tenantKey, CatalogKey.DEFAULT), "1.0.1").execute()
            tenant to variant
        }
        val test = EnvironmentKey.of("test")
        fun generate(environment: EnvironmentKey?, version: String? = null) = withMediator {
            GenerateDocument(catalog = testCatalog(tenant, CatalogKey.DEFAULT), templateId = variant.templateId.key, variantId = variant.key, environmentId = environment, releaseVersion = version, data = objectMapper.createObjectNode(), filename = null).execute()
        }
        fun deployments(): Map<String, CatalogDeployment> = withMediator { ListDeployments(tenant, environmentKey = test).query() }.filter { !it.isDraft }.associateBy { it.version!! }

        generate(test)
        generate(test, "1.0.0")
        generate(environment = null)
        withMediator {
            GenerateDocumentBatch(
                tenant,
                List(2) { BatchGenerationItem(templateId = variant.templateId.key, variantId = variant.key, data = objectMapper.createObjectNode(), filename = null, environmentId = test, catalog = variant.templateId.catalogId) },
            ).execute()
        }
        usage.flush()

        assertThat(deployments().getValue("1.0.1").documentsRequested).`as`("one single, two in a batch").isEqualTo(3)
        assertThat(deployments().getValue("1.0.1").lastUsedAt).isNotNull()
        assertThat(deployments().getValue("1.0.0").documentsRequested).`as`("the request naming it exactly").isEqualTo(1)

        withMediator {
            UndeployCatalogRelease(EnvironmentId(test, TenantId(tenant)), testCatalog(EnvironmentId(test, TenantId(tenant)).tenantKey, CatalogKey.DEFAULT), "1.0.0").execute()
            DeployCatalogRelease(EnvironmentId(test, TenantId(tenant)), testCatalog(EnvironmentId(test, TenantId(tenant)).tenantKey, CatalogKey.DEFAULT), "1.0.0").execute()
        }
        assertThat(deployments().getValue("1.0.0").documentsRequested).`as`("a new deployment starts again").isZero()
        assertThat(deployments().getValue("1.0.0").lastUsedAt).isNull()
    }
}
