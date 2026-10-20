// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.queries

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
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
        mediator.send(DeployCatalogVersion(staging, templateId.catalogId.key, version))
        assertThat(steps().hasDeployed).isTrue()
        assertThat(steps().complete).isFalse()

        mediator.send(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = templateId.key,
                variantId = variantId.key,
                environmentId = staging.key,
                data = ObjectMapper().createObjectNode(),
                filename = null,
                catalogKey = CatalogKey.DEFAULT,
            ),
        )
        drainGenerationJobs(tenant.id)
        assertThat(steps().complete).isTrue()
    }
}
