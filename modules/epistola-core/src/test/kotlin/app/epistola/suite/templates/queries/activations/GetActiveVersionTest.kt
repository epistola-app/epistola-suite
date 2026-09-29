// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries.activations

import app.epistola.suite.catalog.commands.ImportStatus
import app.epistola.suite.catalog.commands.ImportTemplateInput
import app.epistola.suite.catalog.commands.ImportTemplates
import app.epistola.suite.catalog.commands.ImportVariantInput
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class GetActiveVersionTest : IntegrationTestBase() {

    private val templateModel = TestTemplateBuilder.buildMinimal()

    @Test
    fun `returns correct version when multiple templates share the same variant slug`() {
        val tenant = createTenant("ActiveVersion Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val envKey = TestIdHelpers.nextEnvironmentId()
            CreateEnvironment(id = EnvironmentId(envKey, tenantId), name = "Production").execute()

            val slug1 = TestIdHelpers.nextTemplateId().value
            val slug2 = TestIdHelpers.nextTemplateId().value

            // Import two templates both with variant "default", published to the same environment
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug1,
                        name = "Template One",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = listOf(envKey.value),
                    ),
                    ImportTemplateInput(
                        slug = slug2,
                        name = "Template Two",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = listOf(envKey.value),
                    ),
                ),
            ).execute()

            assertThat(results).allSatisfy { assertThat(it.status).isNotEqualTo(ImportStatus.FAILED) }

            // GetActiveVersion for template 1 should return exactly one result, not cross-match template 2
            val templateId1 = TemplateId(TemplateKey.of(slug1), CatalogId.default(tenantId))
            val variantId1 = VariantId(VariantKey.of("default"), templateId1)
            val environmentId = EnvironmentId(envKey, tenantId)

            val activeVersion1 = GetActiveVersion(variantId = variantId1, environmentId = environmentId).query()
            assertThat(activeVersion1).isNotNull

            // GetActiveVersion for template 2 should also return exactly one result
            val templateId2 = TemplateId(TemplateKey.of(slug2), CatalogId.default(tenantId))
            val variantId2 = VariantId(VariantKey.of("default"), templateId2)

            val activeVersion2 = GetActiveVersion(variantId = variantId2, environmentId = environmentId).query()
            assertThat(activeVersion2).isNotNull
        }
    }

    @Test
    fun `returns the same version as reading it directly, including its contract version`() {
        val tenant = createTenant("ActiveVersion Contract Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val envKey = TestIdHelpers.nextEnvironmentId()
            CreateEnvironment(id = EnvironmentId(envKey, tenantId), name = "Production").execute()

            val slug = TestIdHelpers.nextTemplateId().value
            val dataModel = ObjectMapper().readValue(
                """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                ObjectNode::class.java,
            )
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Contracted Template",
                        version = "1.0.0",
                        dataModel = dataModel,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = listOf(envKey.value),
                    ),
                ),
            ).execute()
            assertThat(results).allSatisfy { assertThat(it.status).isNotEqualTo(ImportStatus.FAILED) }

            val variantId = VariantId(VariantKey.of("default"), TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId)))
            val activeVersion = GetActiveVersion(variantId = variantId, environmentId = EnvironmentId(envKey, tenantId)).query()!!

            // Generation and preview validate data only when the version carries its contract version.
            assertThat(activeVersion.contractVersion).isNotNull
            assertThat(activeVersion).isEqualTo(GetVersion(VersionId(activeVersion.id, variantId)).query())
        }
    }
}
