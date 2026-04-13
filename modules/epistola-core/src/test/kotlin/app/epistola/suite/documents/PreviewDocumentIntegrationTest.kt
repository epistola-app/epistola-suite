package app.epistola.suite.documents

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.documents.queries.PreviewDraft
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Timeout(30)
class PreviewDocumentIntegrationTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()

    private fun emptyData(): ObjectNode = objectMapper.createObjectNode()

    @Nested
    inner class PreviewDraftTests {

        @Test
        fun `preview draft returns PDF bytes`() = scenario {
            given {
                val tenant = tenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "Test Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)
                val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
                val version = version(compositeVariantId, templateModel)
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                query(
                    PreviewDraft(
                        tenantId = setup.tenant.id,
                        templateId = setup.template.id,
                        variantId = setup.variant.id,
                        data = emptyData(),
                    ),
                )
            }.then { _, pdfBytes ->
                assertThat(pdfBytes).isNotEmpty()
                // PDF magic bytes: %PDF
                assertThat(pdfBytes[0]).isEqualTo(0x25.toByte()) // %
                assertThat(pdfBytes[1]).isEqualTo(0x50.toByte()) // P
                assertThat(pdfBytes[2]).isEqualTo(0x44.toByte()) // D
                assertThat(pdfBytes[3]).isEqualTo(0x46.toByte()) // F
            }
        }

        @Test
        fun `preview draft with live template model`() = scenario {
            given {
                val tenant = tenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "Test Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)
                val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
                val version = version(compositeVariantId, templateModel)
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                val liveModel = TestTemplateBuilder.buildMinimal(name = "Live Preview Model")
                query(
                    PreviewDraft(
                        tenantId = setup.tenant.id,
                        templateId = setup.template.id,
                        variantId = setup.variant.id,
                        data = emptyData(),
                        templateModel = liveModel,
                    ),
                )
            }.then { _, pdfBytes ->
                assertThat(pdfBytes).isNotEmpty()
                assertThat(pdfBytes[0]).isEqualTo(0x25.toByte()) // %PDF
            }
        }

        @Test
        fun `preview draft throws when no draft exists`() {
            val tenant = createTenant("Test Tenant")
            withAuthentication {
                assertThatThrownBy {
                    mediator.query(
                        PreviewDraft(
                            tenantId = tenant.id,
                            templateId = app.epistola.suite.common.ids.TemplateKey.of("nonexistent"),
                            variantId = app.epistola.suite.common.ids.VariantKey.of("nonexistent"),
                            data = emptyData(),
                        ),
                    )
                }.isInstanceOf(IllegalStateException::class.java)
            }
        }

        @Test
        fun `preview draft validates data against schema`() = scenario {
            given {
                val tenant = tenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "Test Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)
                val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
                val version = version(compositeVariantId, templateModel)

                // Add schema that requires 'name' field
                val dataModel = objectMapper.readTree(
                    """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                )
                execute(
                    UpdateDocumentTemplate(
                        id = compositeTemplateId,
                        dataModel = objectMapper.valueToTree(dataModel),
                    ),
                )
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                setup
            }.then { setup, _ ->
                // Empty data should fail validation (missing required 'name')
                assertThatThrownBy {
                    query(
                        PreviewDraft(
                            tenantId = setup.tenant.id,
                            templateId = setup.template.id,
                            variantId = setup.variant.id,
                            data = emptyData(),
                        ),
                    )
                }.isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Data validation failed")
                    .hasMessageContaining("name")
            }
        }
    }

    @Nested
    inner class PreviewDocumentTests {

        @Test
        fun `preview published version returns PDF bytes`() = scenario {
            given {
                val tenant = tenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "Test Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)
                val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
                val version = version(compositeVariantId, templateModel)
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                query(
                    PreviewDocument(
                        tenantId = setup.tenant.id,
                        templateId = setup.template.id,
                        variantId = setup.variant.id,
                        versionId = setup.version.id,
                        data = emptyData(),
                    ),
                )
            }.then { _, pdfBytes ->
                assertThat(pdfBytes).isNotEmpty()
                assertThat(pdfBytes[0]).isEqualTo(0x25.toByte()) // %PDF
            }
        }

        @Test
        fun `preview throws when version not found`() = scenario {
            given {
                val tenant = tenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "Test Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)
                val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
                val version = version(compositeVariantId, templateModel)
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                setup
            }.then { setup, _ ->
                assertThatThrownBy {
                    query(
                        PreviewDocument(
                            tenantId = setup.tenant.id,
                            templateId = setup.template.id,
                            variantId = setup.variant.id,
                            versionId = app.epistola.suite.common.ids.VersionKey.of(199),
                            data = emptyData(),
                        ),
                    )
                }.isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Version")
                    .hasMessageContaining("not found")
            }
        }

        @Test
        fun `preview requires versionId or environmentId`() = scenario {
            given {
                val tenant = tenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "Test Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)
                val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
                val version = version(compositeVariantId, templateModel)
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                setup
            }.then { setup, _ ->
                assertThatThrownBy {
                    query(
                        PreviewDocument(
                            tenantId = setup.tenant.id,
                            templateId = setup.template.id,
                            variantId = setup.variant.id,
                            data = emptyData(),
                        ),
                    )
                }.isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("versionId or environmentId")
            }
        }

        @Test
        fun `preview validates data against schema`() = scenario {
            given {
                val tenant = tenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "Test Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)
                val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
                val version = version(compositeVariantId, templateModel)

                val dataModel = objectMapper.readTree(
                    """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                )
                execute(
                    UpdateDocumentTemplate(
                        id = compositeTemplateId,
                        dataModel = objectMapper.valueToTree(dataModel),
                    ),
                )
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                setup
            }.then { setup, _ ->
                assertThatThrownBy {
                    query(
                        PreviewDocument(
                            tenantId = setup.tenant.id,
                            templateId = setup.template.id,
                            variantId = setup.variant.id,
                            versionId = setup.version.id,
                            data = emptyData(),
                        ),
                    )
                }.isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Data validation failed")
            }
        }
    }
}
