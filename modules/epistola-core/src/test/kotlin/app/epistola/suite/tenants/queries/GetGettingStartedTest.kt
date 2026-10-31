// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.queries

import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class GetGettingStartedTest : IntegrationTestBase() {

    @Test
    fun `each step is done once the tenant has taken it`(): Unit = withAuthentication {
        val tenant = createTenant("Getting Started")
        val tenantId = TenantId(tenant.id)
        val steps = { mediator.query(GetGettingStarted(tenant.id)) }

        assertThat(steps()).isEqualTo(GettingStarted(hasTemplate = false, hasPublished = false, hasDeployed = false, hasDocument = false))

        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        mediator.send(CreateDocumentTemplate(id = templateId, name = "Letter"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))
        mediator.send(UpdateVariantContent(variantId = variantId, templateModel = TestTemplateBuilder.buildMinimal(name = "Letter")))
        assertThat(steps().hasTemplate).isTrue()
        assertThat(steps().hasPublished).isFalse()

        val version = mediator.publishAndRelease(variantId)
        assertThat(steps().hasPublished).isTrue()

        val staging = EnvironmentId(EnvironmentKey.of("staging"), tenantId)
        mediator.send(CreateEnvironment(staging, "Staging"))
        mediator.send(DeployCatalogRelease(staging, templateId.catalogId, version))
        assertThat(steps().hasDeployed).isTrue()
        assertThat(steps().complete).isFalse()

        mediator.send(
            GenerateDocument(
                catalog = testCatalog(tenant.id, CatalogKey.DEFAULT),
                templateId = templateId.key,
                variantId = variantId.key,
                environmentId = staging.key,
                data = ObjectMapper().createObjectNode(),
                filename = null,
            ),
        )
        drainGenerationJobs(tenant.id)
        assertThat(steps().complete).isTrue()
    }

    @Test
    fun `a supplied catalog deployed does not count as the tenant deploying its own`(): Unit = withAuthentication {
        val tenant = createTenant("Getting Started Supplied")
        val tenantId = TenantId(tenant.id)
        val system = testCatalog(tenant.id, CatalogKey.of("system")).let { CatalogId(CatalogIdentityProperties.BUNDLED_NAMESPACE, it.key, it.tenantId) }
        val staging = EnvironmentId(EnvironmentKey.of("staging"), tenantId)
        mediator.send(CreateEnvironment(staging, "Staging"))

        val release = mediator.query(ListCatalogReleases(system, CatalogReleaseStatus.RELEASED)).single()
        mediator.send(DeployCatalogRelease(staging, system, release.version!!))

        assertThat(mediator.query(GetGettingStarted(tenant.id)).hasDeployed)
            .`as`("step 3 waits for the tenant's own catalog, as steps 1 and 2 do").isFalse()
    }
}
