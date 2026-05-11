package app.epistola.suite.documents

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Timeout(30)
class PreviewDocumentIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()

    /**
     * Inserts a draft contract version with the given data model into contract_versions.
     */
    private fun insertDraftContract(templateId: TemplateId, dataModel: String) {
        jdbi.withHandle<Unit, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO contract_versions (id, tenant_key, catalog_key, template_key, data_model, data_examples, status, created_at)
                VALUES (1, :tenantKey, :catalogKey, :templateKey, :dataModel::jsonb, '[]'::jsonb, 'draft', NOW())
                ON CONFLICT (tenant_key, catalog_key, template_key, id)
                DO UPDATE SET data_model = :dataModel::jsonb
                """,
            )
                .bind("tenantKey", templateId.tenantKey)
                .bind("catalogKey", templateId.catalogKey)
                .bind("templateKey", templateId.key)
                .bind("dataModel", dataModel)
                .execute()
        }
    }

    private fun emptyData(): ObjectNode = objectMapper.createObjectNode()

    @Nested
    inner class FreshTemplatePreviewTests {
        @Test
        fun `preview works on freshly created template without any edits`() {
            // Mimics: create template → open editor → click preview
            val tenant = createTenant("Fresh Preview Tenant")
            val tenantId = TenantId(tenant.id)
            val catalogId = CatalogId.default(tenantId)
            val templateId = TemplateId(app.epistola.suite.common.ids.TemplateKey.of("fresh-preview"), catalogId)

            withMediator {
                app.epistola.suite.templates.commands.CreateDocumentTemplate(
                    id = templateId,
                    name = "Fresh Template",
                ).execute()
            }

            // Preview the default variant's draft — no contract edits, no version edits
            val defaultVariantId = app.epistola.suite.common.ids.VariantKey.of("fresh-preview-default")
            val pdfBytes = withMediator {
                PreviewVariant(
                    tenantId = tenant.id,
                    catalogKey = app.epistola.suite.common.ids.CatalogKey.DEFAULT,
                    templateId = templateId.key,
                    variantId = defaultVariantId,
                    data = emptyData(),
                ).query()
            }

            assertThat(pdfBytes).isNotEmpty()
        }
    }

    @Nested
    inner class PreviewVariantTests {

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
                    PreviewVariant(
                        tenantId = setup.tenant.id,
                        catalogKey = CatalogKey.DEFAULT,
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
                    PreviewVariant(
                        tenantId = setup.tenant.id,
                        catalogKey = CatalogKey.DEFAULT,
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
                        PreviewVariant(
                            tenantId = tenant.id,
                            catalogKey = CatalogKey.DEFAULT,
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

                // Add schema that requires 'name' field via contract version
                app.epistola.suite.templates.contracts.commands.UpdateContractVersion(
                    templateId = compositeTemplateId,
                    dataModel = objectMapper.readValue(
                        """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                        tools.jackson.databind.node.ObjectNode::class.java,
                    ),
                ).execute()
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                setup
            }.then { setup, _ ->
                // Empty data should fail validation (missing required 'name')
                assertThatThrownBy {
                    query(
                        PreviewVariant(
                            tenantId = setup.tenant.id,
                            catalogKey = CatalogKey.DEFAULT,
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

        @Test
        fun `preview rejects rich-text data that violates the registered ref schema`() = scenario {
            given {
                val tenant = tenant("RichText Validation Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "RichText Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)
                val templateModel = TestTemplateBuilder.buildMinimal(name = "RichText Template")
                val version = version(compositeVariantId, templateModel)

                // Contract declares `greeting` as a richTextInline value (single paragraph).
                app.epistola.suite.templates.contracts.commands.UpdateContractVersion(
                    templateId = compositeTemplateId,
                    dataModel = objectMapper.readValue(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "greeting": { "${"$"}ref": "https://epistola.app/schemas/richtext-inline-v1.json" }
                          }
                        }
                        """.trimIndent(),
                        tools.jackson.databind.node.ObjectNode::class.java,
                    ),
                ).execute()
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                setup
            }.then { setup, _ ->
                // A multi-paragraph doc violates the inline single-paragraph constraint.
                val multiParagraphDoc = objectMapper.readValue(
                    """
                    {
                      "greeting": {
                        "type": "doc",
                        "content": [
                          { "type": "paragraph", "content": [{ "type": "text", "text": "first" }] },
                          { "type": "paragraph", "content": [{ "type": "text", "text": "second" }] }
                        ]
                      }
                    }
                    """.trimIndent(),
                    tools.jackson.databind.node.ObjectNode::class.java,
                )
                assertThatThrownBy {
                    query(
                        PreviewVariant(
                            tenantId = setup.tenant.id,
                            catalogKey = CatalogKey.DEFAULT,
                            templateId = setup.template.id,
                            variantId = setup.variant.id,
                            data = multiParagraphDoc,
                        ),
                    )
                }.isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Data validation failed")
                    .hasMessageContaining("greeting")
            }
        }

        @Test
        fun `preview renders a richTextVariable node bound to a richTextBlock contract field`() = scenario {
            given {
                val tenant = tenant("RichText Variable Tenant")
                val tenantId = TenantId(tenant.id)
                val template = template(tenant.id, "RichText Variable Template")
                val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
                val variant = variant(compositeTemplateId, "Default")
                val compositeVariantId = VariantId(variant.id, compositeTemplateId)

                // Template: root → richTextVariable bound to "bio"
                val rootId = "root-rtv"
                val rootSlot = "root-slot-rtv"
                val rtvId = "rtv-1"
                val templateModel = app.epistola.template.model.TemplateDocument(
                    modelVersion = 1,
                    root = rootId,
                    nodes = mapOf(
                        rootId to app.epistola.template.model.Node(
                            id = rootId,
                            type = "root",
                            slots = listOf(rootSlot),
                        ),
                        rtvId to app.epistola.template.model.Node(
                            id = rtvId,
                            type = "richTextVariable",
                            slots = emptyList(),
                            props = mapOf("binding" to "bio"),
                        ),
                    ),
                    slots = mapOf(
                        rootSlot to app.epistola.template.model.Slot(
                            id = rootSlot,
                            nodeId = rootId,
                            name = "children",
                            children = listOf(rtvId),
                        ),
                    ),
                    themeRef = app.epistola.template.model.ThemeRef.Inherit,
                )
                val version = version(compositeVariantId, templateModel)

                // Contract: bio is a richTextBlock value.
                app.epistola.suite.templates.contracts.commands.UpdateContractVersion(
                    templateId = compositeTemplateId,
                    dataModel = objectMapper.readValue(
                        """
                            {
                              "type": "object",
                              "properties": {
                                "bio": { "${"$"}ref": "https://epistola.app/schemas/richtext-block-v1.json" }
                              }
                            }
                        """.trimIndent(),
                        tools.jackson.databind.node.ObjectNode::class.java,
                    ),
                ).execute()
                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                val data = objectMapper.readValue(
                    """
                        {
                          "bio": {
                            "type": "doc",
                            "content": [
                              {
                                "type": "paragraph",
                                "content": [
                                  { "type": "text", "text": "Founded in " },
                                  { "type": "text", "text": "1999", "marks": [{ "type": "strong" }] }
                                ]
                              },
                              {
                                "type": "bullet_list",
                                "content": [
                                  {
                                    "type": "list_item",
                                    "content": [
                                      {
                                        "type": "paragraph",
                                        "content": [{ "type": "text", "text": "Quality" }]
                                      }
                                    ]
                                  }
                                ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    tools.jackson.databind.node.ObjectNode::class.java,
                )
                query(
                    PreviewVariant(
                        tenantId = setup.tenant.id,
                        catalogKey = CatalogKey.DEFAULT,
                        templateId = setup.template.id,
                        variantId = setup.variant.id,
                        data = data,
                    ),
                )
            }.then { _, pdfBytes ->
                // PDF rendered through DirectPdfRenderer + RichTextVariableRenderer.
                // Structural check: non-empty %PDF-marker'd byte stream, larger than
                // the empty-content baseline so we know the rendered block landed.
                assertThat(pdfBytes).isNotEmpty()
                assertThat(String(pdfBytes.copyOfRange(0, 4))).isEqualTo("%PDF")
                assertThat(pdfBytes.size).isGreaterThan(1000)
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
                        catalogKey = CatalogKey.DEFAULT,
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
                            catalogKey = CatalogKey.DEFAULT,
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

                // Add schema that requires 'name' field via contract version and publish
                app.epistola.suite.templates.contracts.commands.UpdateContractVersion(
                    templateId = compositeTemplateId,
                    dataModel = objectMapper.readValue(
                        """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                        tools.jackson.databind.node.ObjectNode::class.java,
                    ),
                ).execute()
                app.epistola.suite.templates.contracts.commands.PublishContractVersion(
                    templateId = compositeTemplateId,
                ).execute()

                DocumentSetup(tenant, template, variant, version)
            }.whenever { setup ->
                setup
            }.then { setup, _ ->
                // Use PreviewVariant instead of PreviewDocument to test schema validation
                // PreviewVariant uses the latest contract directly (not via version FK)
                assertThatThrownBy {
                    query(
                        PreviewVariant(
                            tenantId = setup.tenant.id,
                            catalogKey = CatalogKey.DEFAULT,
                            templateId = setup.template.id,
                            variantId = setup.variant.id,
                            data = emptyData(),
                        ),
                    )
                }.isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Data validation failed")
            }
        }
    }
}
