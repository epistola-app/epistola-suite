// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.ImportStatus
import app.epistola.suite.templates.commands.ImportTemplateInput
import app.epistola.suite.templates.commands.ImportTemplates
import app.epistola.suite.templates.commands.ImportVariantInput
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Tests that verify contract schema is preserved across repeated catalog deployments.
 * Reproduces a bug where deploying a catalog multiple times through the API
 * caused the schema (data contract) to disappear.
 */
class RepeatedCatalogDeploymentTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()
    private val templateModel = TestTemplateBuilder.buildMinimal()

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    private fun buildImportInput(
        slug: String,
        dataModel: ObjectNode?,
        dataExamples: List<DataExample> = emptyList(),
    ) = ImportTemplateInput(
        slug = slug,
        name = "Test Template",
        version = "1.0.0",
        dataModel = dataModel,
        dataExamples = dataExamples,
        templateModel = templateModel,
        variants = listOf(
            ImportVariantInput(id = "$slug-default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
        ),
    )

    @Test
    fun `repeated import preserves contract schema`() {
        val tenant = createTenant("Repeated Deploy Test")
        val tenantId = TenantId(tenant.id)
        val slug = TestIdHelpers.nextTemplateId().value
        val dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"}}}""")

        withMediator {
            // First import with schema
            val result1 = ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel)),
            ).execute()
            assertThat(result1[0].status).isEqualTo(ImportStatus.CREATED)

            val templateId = TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId))
            val contract1 = GetDataContract(templateId).query()
            assertThat(contract1).isNotNull
            assertThat(contract1!!.dataModel).isNotNull

            // Second import with same schema
            val result2 = ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel)),
            ).execute()
            assertThat(result2[0].status).isEqualTo(ImportStatus.UPDATED)

            val contract2 = GetDataContract(templateId).query()
            assertThat(contract2).isNotNull
            assertThat(contract2!!.dataModel).isNotNull
            assertThat(contract2.dataModel.toString()).isEqualTo(dataModel.toString())

            // Third import with same schema
            val result3 = ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel)),
            ).execute()
            assertThat(result3[0].status).isEqualTo(ImportStatus.UPDATED)

            val contract3 = GetDataContract(templateId).query()
            assertThat(contract3).isNotNull
            assertThat(contract3!!.dataModel).isNotNull
            assertThat(contract3.dataModel.toString()).isEqualTo(dataModel.toString())
        }
    }

    @Test
    fun `repeated import preserves data examples`() {
        val tenant = createTenant("Repeated Deploy Examples Test")
        val tenantId = TenantId(tenant.id)
        val slug = TestIdHelpers.nextTemplateId().value
        val dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
        val examples = listOf(
            DataExample(id = "ex1", name = "Example 1", data = schema("""{"name":"John"}""")),
            DataExample(id = "ex2", name = "Example 2", data = schema("""{"name":"Jane"}""")),
        )

        withMediator {
            // First import
            ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel, dataExamples = examples)),
            ).execute()

            val templateId = TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId))
            val contract1 = GetDataContract(templateId).query()
            assertThat(contract1!!.dataExamples).hasSize(2)

            // Second import
            ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel, dataExamples = examples)),
            ).execute()

            val contract2 = GetDataContract(templateId).query()
            assertThat(contract2!!.dataExamples).hasSize(2)

            // Third import
            ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel, dataExamples = examples)),
            ).execute()

            val contract3 = GetDataContract(templateId).query()
            assertThat(contract3!!.dataExamples).hasSize(2)
        }
    }

    @Test
    fun `repeated import without schema creates empty contract replacing previous`() {
        val tenant = createTenant("No Schema Reimport Test")
        val tenantId = TenantId(tenant.id)
        val slug = TestIdHelpers.nextTemplateId().value
        val dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")

        withMediator {
            // First import WITH schema
            ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel)),
            ).execute()

            val templateId = TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId))
            val contract1 = GetDataContract(templateId).query()
            assertThat(contract1!!.dataModel).isNotNull

            // Second import WITHOUT schema — creates a new empty contract
            ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel = null)),
            ).execute()

            // The contract now has a null dataModel (the import had no schema)
            val contract2 = GetDataContract(templateId).query()
            assertThat(contract2).isNotNull
            assertThat(contract2!!.dataModel).isNull()
        }
    }

    @Test
    fun `full catalog install then upgrade preserves schema`() {
        val tenant = createTenant("Catalog Upgrade Schema Test")
        val tenantId = TenantId(tenant.id)
        val catalogKey = app.epistola.suite.common.ids.CatalogKey.of("epistola-demo")

        withMediator {
            // Register and install the demo catalog
            RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = DEMO_CATALOG_URL,
                authType = app.epistola.suite.catalog.AuthType.NONE,
            ).execute()

            InstallFromCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()

            // Check schema exists for a template that has one (hello-world)
            val templateId = TemplateId(TemplateKey.of("hello-world"), testCatalog(tenantId, catalogKey))
            val contract1 = GetDataContract(templateId).query()

            // Upgrade (reinstall)
            UpgradeCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()

            val contract2 = GetDataContract(templateId).query()

            // If first install had a contract, upgrade should preserve it
            if (contract1 != null) {
                assertThat(contract2).isNotNull
                assertThat(contract2!!.dataModel).isNotNull
                assertThat(contract2.dataModel.toString()).isEqualTo(contract1.dataModel.toString())
            }
        }
    }

    @Test
    fun `import after upgrade creates contract for pre-existing template without contract versions`() {
        val tenant = createTenant("Post-Upgrade Import Test")
        val tenantId = TenantId(tenant.id)
        val slug = TestIdHelpers.nextTemplateId().value
        val dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")

        withMediator {
            // A template without a data contract, as an import of an old one leaves it.
            ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Pre-Migration Template",
                        version = "1.0.0",
                        dataModel = null, // No contract data (simulates post-migration state)
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "$slug-default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                    ),
                ),
            ).execute()

            val templateId = TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId))

            // The template has a contract, but no schema
            val contractBefore = GetDataContract(templateId).query()
            assertThat(contractBefore).isNotNull
            assertThat(contractBefore!!.dataModel).isNull()

            // Now deploy again WITH the schema (simulating external system sending full catalog)
            ImportTemplates(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                templates = listOf(buildImportInput(slug, dataModel)),
            ).execute()

            // Contract should now exist
            val contractAfter = GetDataContract(templateId).query()
            assertThat(contractAfter).isNotNull
            assertThat(contractAfter!!.dataModel).isNotNull
            assertThat(contractAfter.dataModel.toString()).isEqualTo(dataModel.toString())
        }
    }

    @Test
    fun `export after upgrade without contract produces ZIP with null dataModel`() {
        val tenant = createTenant("Export No Contract Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("export-no-contract")
        val catalogId = testCatalog(tenantId, catalogKey)

        withMediator {
            // Create a catalog with a template but WITHOUT any contract
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "No Contract Catalog").execute()
            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogId)
            CreateDocumentTemplate(id = templateId, name = "No Contract Template").execute()

            // Export the catalog — should succeed even without contract
            val exportResult = mediator.exportReleased(testCatalog(app.epistola.suite.common.ids.TenantId(tenantKey), catalogKey))
            assertThat(exportResult.zipBytes).isNotEmpty()

            // Re-import the exported ZIP multiple times
            repeat(3) { iteration ->
                val result = ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = exportResult.zipBytes,
                    provenance = ReleaseProvenance(ReleaseChannel.UPLOAD),
                ).execute()
                assertThat(result.results).allSatisfy { r ->
                    assertThat(r.status)
                        .describedAs("Import #${iteration + 1} should not fail")
                        .isNotEqualTo(InstallStatus.FAILED)
                }
            }

            // The contract still has no schema (none was ever set)
            val contract = GetDataContract(templateId).query()
            assertThat(contract!!.dataModel).isNull()
        }
    }

    @Test
    fun `repeated ZIP import via API preserves schema`() {
        val tenant = createTenant("ZIP Repeated Deploy Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("zip-deploy-test")
        val catalogId = testCatalog(tenantId, catalogKey)

        withMediator {
            // Create an authored catalog with a template that has a contract
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "ZIP Deploy Catalog").execute()

            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogId)
            CreateDocumentTemplate(id = templateId, name = "ZIP Template").execute().withRequiredDataExample()

            // Create a theme (so export has multiple resource types)
            val themeKey = ThemeKey.of("zip-theme")
            CreateTheme(id = ThemeId(themeKey, catalogId), name = "ZIP Theme").execute()
            UpdateDocumentTemplate(id = templateId, themeId = themeKey, themeCatalog = testCatalog(templateId.tenantKey, catalogKey)).execute()

            // Add contract data
            val contractSchema = schema("""{"type":"object","properties":{"invoice_number":{"type":"string"},"total":{"type":"number"}},"required":["invoice_number"]}""")
            UpdateDataContract(
                templateId = templateId,
                dataModel = contractSchema,
                dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        "example-1",
                        "Example 1",
                        schema("""{"invoice_number":"INV-1","total":42}"""),
                    ),
                ),
            ).execute()

            // Export as ZIP
            val exportResult = mediator.exportReleased(testCatalog(app.epistola.suite.common.ids.TenantId(tenantKey), catalogKey))
            val zipBytes = exportResult.zipBytes

            // Verify contract before re-imports
            val contractBefore = GetDataContract(templateId).query()
            assertThat(contractBefore).isNotNull
            assertThat(contractBefore!!.dataModel).isNotNull

            // Import the same ZIP 3 times (simulating repeated API deployments)
            repeat(3) { iteration ->
                val result = ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = zipBytes,
                    provenance = ReleaseProvenance(ReleaseChannel.UPLOAD),
                ).execute()

                assertThat(result.results).allSatisfy { r ->
                    assertThat(r.status).isNotEqualTo(InstallStatus.FAILED)
                }

                // Verify contract after each import
                val contract = GetDataContract(templateId).query()
                assertThat(contract)
                    .describedAs("Contract should exist after import #${iteration + 1}")
                    .isNotNull
                assertThat(contract!!.dataModel)
                    .describedAs("Contract dataModel should not be null after import #${iteration + 1}")
                    .isNotNull
                assertThat(contract.dataModel!!.has("properties"))
                    .describedAs("Contract should have properties after import #${iteration + 1}")
                    .isTrue()
                assertThat(contract.dataModel.get("properties").has("invoice_number"))
                    .describedAs("Contract should have invoice_number after import #${iteration + 1}")
                    .isTrue()
            }
        }
    }
}

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"
