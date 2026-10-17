// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.ExportStencils
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

class CatalogExportImportTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jsonSchemaValidator: JsonSchemaValidator

    @Test
    fun `catalog metadata survives export and import into another tenant`() {
        val source = createTenant("Metadata Source")
        val target = createTenant("Metadata Target")
        val catalogKey = CatalogKey.of("metadata-catalog")

        val export = withMediator {
            CreateCatalog(source.id, catalogKey, "Metadata Catalog").execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("audience"), CatalogId(catalogKey, TenantId(source.id))),
                displayName = "Audience",
                allowedValues = listOf("municipalities"),
            ).execute()
            val image = UploadAsset(
                tenantId = source.id,
                name = "Catalog cover",
                mediaType = AssetMediaType.PNG,
                content = createMinimalPng(),
                width = 1,
                height = 1,
                catalogKey = catalogKey,
            ).execute()
            val imageSlug = image.id.value

            UpdateCatalogMetadata(
                tenantKey = source.id,
                catalogKey = catalogKey,
                name = "Metadata Catalog Renamed",
                description = "Portable discovery metadata",
                attributes = listOf(AttributeAssignment(catalogKey.value, "audience", "municipalities")),
                keywords = linkedSetOf("letters", "municipal"),
                presentation = CatalogPresentation(imageSlug, listOf(imageSlug)),
                license = CatalogLicense(
                    name = "Creative Commons Attribution 4.0",
                    spdxExpression = "CC-BY-4.0",
                    url = "https://creativecommons.org/licenses/by/4.0/",
                    copyrightText = "Copyright Epistola",
                ),
            ).execute()

            val exported = ExportCatalogZip(source.id, catalogKey).execute()
            assertThatThrownBy { DeleteAsset(source.id, image.id).execute() }
                .isInstanceOf(AssetInUseException::class.java)
                .hasMessageContaining("Catalog: Metadata Catalog Renamed")
            exported
        }

        withMediator {
            ImportCatalogZip(target.id, export.zipBytes, CatalogType.AUTHORED).execute()
            val imported = GetCatalog(target.id, catalogKey).query()!!

            assertThat(imported.name).isEqualTo("Metadata Catalog Renamed")
            assertThat(imported.description).isEqualTo("Portable discovery metadata")
            assertThat(imported.catalogMetadata.attributes)
                .containsExactly(AttributeAssignment(catalogKey.value, "audience", "municipalities"))
            assertThat(imported.catalogMetadata.keywords).containsExactlyInAnyOrder("letters", "municipal")
            assertThat(imported.catalogMetadata.presentation?.iconImageKey).isNotNull()
            assertThat(imported.catalogMetadata.presentation?.imageKeys)
                .containsExactly(imported.catalogMetadata.presentation?.iconImageKey)
            assertThat(imported.catalogMetadata.license?.spdxExpression).isEqualTo("CC-BY-4.0")
            assertThat(imported.catalogMetadata.license?.url)
                .isEqualTo("https://creativecommons.org/licenses/by/4.0/")
        }
    }

    @Test
    fun `export and import round-trip preserves all resources`() {
        val tenant = createTenant("Round-Trip Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("roundtrip-catalog")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            // Create an authored catalog
            CreateCatalog(
                tenantKey = tenantKey,
                id = catalogKey,
                name = "Round-Trip Catalog",
                description = "Test catalog for export/import round-trip",
            ).execute()

            // Create a template
            val templateKey = TestIdHelpers.nextTemplateId()
            CreateDocumentTemplate(
                id = TemplateId(templateKey, catalogId),
                name = "Round-Trip Template",
            ).execute()

            // Create a theme
            val themeKey = ThemeKey.of("roundtrip-theme")
            CreateTheme(
                id = ThemeId(themeKey, catalogId),
                name = "Round-Trip Theme",
                description = "A test theme",
            ).execute()

            // Assign theme to template
            val templateId = TemplateId(templateKey, catalogId)
            UpdateDocumentTemplate(
                id = templateId,
                themeId = themeKey,
                themeCatalogKey = catalogKey,
            ).execute()

            // Create an attribute
            val attrKey = AttributeKey.of("roundtrip-attr")
            CreateAttributeDefinition(
                id = AttributeId(attrKey, catalogId),
                displayName = "Round-Trip Attribute",
                allowedValues = listOf("value-a", "value-b"),
            ).execute()

            // Add a contract with schema and publish it
            val contractSchema = ObjectMapper().readValue(
                """{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"}},"required":["name"]}""",
                ObjectNode::class.java,
            )
            UpdateDataContract(
                templateId = templateId,
                dataModel = contractSchema,
                dataExamples = listOf(
                    DataExample("example-1", "Example 1", ObjectMapper().createObjectNode().put("name", "Ada")),
                ),
            ).execute()

            // Mark the default variant ready, as authoring does before a release.
            MarkVariantReady(VariantId(VariantKey.INITIAL, templateId)).execute()

            // Upload an asset (small PNG stub)
            val pngBytes = createMinimalPng()
            UploadAsset(
                tenantId = tenantKey,
                name = "test-logo",
                mediaType = AssetMediaType.PNG,
                content = pngBytes,
                width = 1,
                height = 1,
                catalogKey = catalogKey,
            ).execute()

            // Export the catalog as ZIP
            val exportResult = ExportCatalogZip(
                tenantKey = tenantKey,
                catalogKey = catalogKey,
            ).execute()

            assertThat(exportResult.zipBytes).isNotEmpty()
            assertThat(exportResult.filename).contains("roundtrip-catalog")

            // Import the ZIP into a NEW catalog
            val importResult = ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = exportResult.zipBytes,
                catalogType = CatalogType.AUTHORED,
            ).execute()

            // The import creates the catalog from the manifest slug, which is the same as the original.
            // Since it already exists as AUTHORED, it updates in place.
            assertThat(importResult.catalogKey).isEqualTo(catalogKey)

            // Verify resources were imported
            val resourceTypes = importResult.results.map { it.type }.toSet()
            assertThat(resourceTypes).contains("template", "theme", "attribute", "image")
            assertThat(importResult.results).allSatisfy { result ->
                assertThat(result.status).isNotEqualTo(InstallStatus.FAILED)
            }

            // Verify the catalog still exists with all resources via browse
            val browseResult = BrowseCatalog(
                tenantKey = tenantKey,
                catalogKey = catalogKey,
            ).query()

            assertThat(browseResult.resources).isNotEmpty()

            // Verify theme reference survived the round-trip
            val reimportedTemplate = GetDocumentTemplate(templateId).query()
            assertThat(reimportedTemplate).isNotNull
            assertThat(reimportedTemplate!!.themeKey).isEqualTo(themeKey)
            assertThat(reimportedTemplate.themeCatalogKey).isEqualTo(catalogKey)

            // Verify contract data survived the round-trip
            val reimportedContract = GetDataContract(templateId = templateId).query()
            assertThat(reimportedContract).isNotNull
            assertThat(reimportedContract!!.dataModel).isNotNull
            val properties = reimportedContract.dataModel!!.get("properties")
            assertThat(properties.has("name")).isTrue()
            assertThat(properties.has("amount")).isTrue()
        }
    }

    @Test
    fun `importing an AUTHORED ZIP over an existing SUBSCRIBED catalog is rejected (type mismatch)`() {
        val tenant = createTenant("Subscribed Reject")
        val tenantKey = tenant.id

        withMediator {
            // Register a subscribed catalog (from demo)
            RegisterCatalog(
                tenantKey = tenantKey,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            // Export the demo catalog to get a valid ZIP
            val exportResult = ExportCatalogZip(
                tenantKey = tenantKey,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).execute()

            // Same slug already exists as SUBSCRIBED → importing it as AUTHORED
            // is a type mismatch (would flip ownership). Re-importing it as
            // SUBSCRIBED is the supported "upgrade from ZIP" path instead.
            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = exportResult.zipBytes,
                    catalogType = CatalogType.AUTHORED,
                ).execute()
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("already exists as SUBSCRIBED")
        }
    }

    @Test
    fun `import ZIP without catalog json is rejected`() {
        val tenant = createTenant("No Manifest")
        val tenantKey = tenant.id

        withMediator {
            // Create a ZIP with no catalog.json
            val zipBytes = createZipWithoutManifest()

            val exception = assertThrows<CatalogImportValidationException> {
                ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = zipBytes,
                    catalogType = CatalogType.AUTHORED,
                ).execute()
            }
            val finding = exception.findings.single()
            assertThat(finding.code).isEqualTo("CATALOG_ARCHIVE_REQUIRED_FILE_MISSING")
            assertThat(finding.path).isEqualTo("catalog.json")
        }
    }

    @Test
    fun `portable catalog validation rejects invalid templates before creating the catalog`() {
        val tenant = createTenant("Portable Validation")
        val catalogKey = CatalogKey.of("invalid-portable-catalog")
        val zipBytes = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("catalog.json"))
                zip.write(
                    """
                    {
                      "schemaVersion": 8,
                      "catalog": {"registry": "exchange.epistola.app", "namespace": "epistola-tests", "key": "${catalogKey.value}", "name": "Invalid portable catalog"},
                      "publisher": {"name": "Epistola tests"},
                      "release": {"version": "1.0.0"},
                      "resources": [
                        {
                          "type": "template",
                          "key": "invoice",
                          "name": "Invoice",
                          "detailUrl": "./resources/template/invoice.json"
                        }
                      ]
                    }
                    """.trimIndent().toByteArray(),
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("resources/template/invoice.json"))
                zip.write(
                    """
                    {
                      "schemaVersion": 8,
                      "resource": {
                        "type": "template",
                        "key": "invoice",
                        "name": "Invoice",
                        "templateModel": {"root": "missing", "nodes": {}, "slots": {}},
                        "variants": []
                      }
                    }
                    """.trimIndent().toByteArray(),
                )
                zip.closeEntry()
            }
        }.toByteArray()

        withMediator {
            val exception = assertThrows<CatalogImportValidationException> {
                ImportCatalogZip(
                    tenantKey = tenant.id,
                    zipBytes = zipBytes,
                    catalogType = CatalogType.AUTHORED,
                ).execute()
            }
            val finding = exception.findings.first { it.code == "TEMPLATE_GRAPH_INVALID" }
            assertThat(finding.path).startsWith("resources/template/invoice.json.resource.templateModel.")

            assertThat(GetCatalog(tenant.id, catalogKey).query()).isNull()
        }
    }

    @Test
    fun `export and import preserves rich-text ref properties verbatim`() {
        val tenant = createTenant("Rich-Text Ref Round-Trip")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("richtext-ref-catalog")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(
                tenantKey = tenantKey,
                id = catalogKey,
                name = "Rich-Text Ref Catalog",
                description = "Round-trip a contract with rich-text \$ref properties",
            ).execute()

            val templateKey = TestIdHelpers.nextTemplateId()
            CreateDocumentTemplate(
                id = TemplateId(templateKey, catalogId),
                name = "Rich-Text Ref Template",
            ).execute()

            val templateId = TemplateId(templateKey, catalogId)

            // Contract: greeting is richTextInline, bio is richTextBlock,
            // taglines is an array of richTextInline values.
            val contractSchema = ObjectMapper().readValue(
                """
                {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string" },
                    "greeting": { "${"$"}ref": "https://epistola.app/schemas/richtext-inline-v1.json" },
                    "bio": { "${"$"}ref": "https://epistola.app/schemas/richtext-block-v1.json" },
                    "taglines": {
                      "type": "array",
                      "items": { "${"$"}ref": "https://epistola.app/schemas/richtext-inline-v1.json" }
                    }
                  },
                  "required": ["name"]
                }
                """.trimIndent(),
                ObjectNode::class.java,
            )
            UpdateDataContract(
                templateId = templateId,
                dataModel = contractSchema,
                dataExamples = listOf(
                    DataExample("example-1", "Example 1", ObjectMapper().createObjectNode().put("name", "Ada")),
                ),
            ).execute()

            // Mark the default variant ready, as authoring does before a release.
            MarkVariantReady(VariantId(VariantKey.INITIAL, templateId)).execute()

            val exportResult = ExportCatalogZip(
                tenantKey = tenantKey,
                catalogKey = catalogKey,
            ).execute()
            assertThat(exportResult.zipBytes).isNotEmpty()

            val importResult = ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = exportResult.zipBytes,
                catalogType = CatalogType.AUTHORED,
            ).execute()
            assertThat(importResult.catalogKey).isEqualTo(catalogKey)
            assertThat(importResult.results).allSatisfy { result ->
                assertThat(result.status).isNotEqualTo(InstallStatus.FAILED)
            }

            // Verify every \$ref URL came back identical.
            val reimportedContract = GetDataContract(templateId = templateId).query()
            val properties = reimportedContract!!.dataModel!!.get("properties")

            val greeting = properties.get("greeting")
            assertThat(greeting.has("\$ref")).isTrue()
            assertThat(greeting.get("\$ref").asString())
                .isEqualTo("https://epistola.app/schemas/richtext-inline-v1.json")

            val bio = properties.get("bio")
            assertThat(bio.has("\$ref")).isTrue()
            assertThat(bio.get("\$ref").asString())
                .isEqualTo("https://epistola.app/schemas/richtext-block-v1.json")

            val taglines = properties.get("taglines")
            assertThat(taglines.get("type").asString()).isEqualTo("array")
            assertThat(taglines.get("items").get("\$ref").asString())
                .isEqualTo("https://epistola.app/schemas/richtext-inline-v1.json")

            // And: the rich-text values themselves still validate against the
            // registered schemas after the round-trip — the registry's $ref
            // resolution survived the import.
            val sampleData = ObjectMapper().readValue(
                """
                {
                  "name": "Acme Corp",
                  "greeting": {
                    "type": "doc",
                    "content": [
                      { "type": "paragraph", "content": [{ "type": "text", "text": "Hi" }] }
                    ]
                  },
                  "bio": {
                    "type": "doc",
                    "content": [
                      { "type": "paragraph", "content": [{ "type": "text", "text": "Founded 1999." }] }
                    ]
                  }
                }
                """.trimIndent(),
                ObjectNode::class.java,
            )
            val errors = jsonSchemaValidator.validate(reimportedContract.dataModel, sampleData)
            assertThat(errors).isEmpty()
        }
    }

    @Test
    fun `requireCatalogEditable passes for authored and throws for subscribed`() {
        val tenant = createTenant("Editable Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)

        withMediator {
            // Create an authored catalog
            val authoredKey = CatalogKey.of("editable-test")
            CreateCatalog(
                tenantKey = tenantKey,
                id = authoredKey,
                name = "Editable Catalog",
            ).execute()

            // Should NOT throw for authored catalog
            requireCatalogEditable(tenantKey, authoredKey)

            // Register a subscribed catalog
            RegisterCatalog(
                tenantKey = tenantKey,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            val subscribedKey = CatalogKey.of("epistola-demo")

            // Should throw for subscribed catalog
            assertThatThrownBy {
                requireCatalogEditable(tenantKey, subscribedKey)
            }.isInstanceOf(CatalogReadOnlyException::class.java)
                .hasMessageContaining("read-only")

            // Inside CatalogImportContext.runAsImport, even subscribed should pass
            CatalogImportContext.runAsImport {
                requireCatalogEditable(tenantKey, subscribedKey)
                // No exception — import context bypasses the check
            }
        }
    }

    @Test
    fun `create catalog and verify via get catalog`() {
        val tenant = createTenant("Create Catalog")
        val tenantKey = tenant.id

        withMediator {
            val catalogKey = CatalogKey.of("test-catalog")
            val catalog = CreateCatalog(
                tenantKey = tenantKey,
                id = catalogKey,
                name = "Test Catalog",
                description = "A test catalog",
            ).execute()

            assertThat(catalog.id).isEqualTo(catalogKey)
            assertThat(catalog.name).isEqualTo("Test Catalog")
            assertThat(catalog.description).isEqualTo("A test catalog")
            assertThat(catalog.type).isEqualTo(CatalogType.AUTHORED)

            // Verify via GetCatalog
            val fetched = GetCatalog(tenantKey, catalogKey).query()
            assertThat(fetched).isNotNull
            assertThat(fetched!!.id).isEqualTo(catalogKey)
            assertThat(fetched.name).isEqualTo("Test Catalog")
            assertThat(fetched.type).isEqualTo(CatalogType.AUTHORED)
        }
    }

    @Test
    fun `create duplicate catalog throws`() {
        val tenant = createTenant("Duplicate Catalog")
        val tenantKey = tenant.id

        withMediator {
            val catalogKey = CatalogKey.of("dup-catalog")
            CreateCatalog(
                tenantKey = tenantKey,
                id = catalogKey,
                name = "First Catalog",
            ).execute()

            assertThatThrownBy {
                CreateCatalog(
                    tenantKey = tenantKey,
                    id = catalogKey,
                    name = "Duplicate Catalog",
                ).execute()
            }.isInstanceOf(Exception::class.java)
        }
    }

    @Test
    fun `export and import preserve same-catalog code list and binding`() {
        val tenant = createTenant("CodeListExport")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val sourceKey = CatalogKey.of("cl-export-source")
        val sourceId = CatalogId(sourceKey, tenantId)

        val zip = withMediator {
            CreateCatalog(tenantKey = tenantKey, id = sourceKey, name = "Code List Export Source").execute()

            CreateCodeList(
                id = CodeListId(CodeListKey.of("regions"), sourceId),
                displayName = "Regions",
                sourceType = CodeListSource.INLINE,
                entries = listOf(
                    CodeListEntry("eu", "Europe"),
                    CodeListEntry("us", "United States"),
                    CodeListEntry("apac", "Asia-Pacific"),
                ),
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("region"), sourceId),
                displayName = "Region",
                codeListId = CodeListId(CodeListKey.of("regions"), sourceId),
            ).execute()

            ExportCatalogZip(tenantKey = tenantKey, catalogKey = sourceKey).execute()
        }

        // Re-import into a second tenant. The exported manifest carries both
        // the code list resource AND the attribute with a same-catalog
        // binding (catalogKey null on the wire), and the importer should
        // wire the FK to the freshly-imported code list inside the new
        // tenant's own copy of the source catalog.
        val target = createTenant("CodeListExport-Target")
        val targetId = TenantId(target.id)

        withMediator {
            val result = CatalogImportContext.runAsImport {
                ImportCatalogZip(
                    tenantKey = target.id,
                    zipBytes = zip.zipBytes,
                    catalogType = CatalogType.AUTHORED,
                ).execute()
            }
            val importedCatalogId = CatalogId(result.catalogKey, targetId)

            val codeList = GetCodeList(CodeListId(CodeListKey.of("regions"), importedCatalogId)).query()
            assertThat(codeList).isNotNull()
            assertThat(codeList!!.displayName).isEqualTo("Regions")

            val entries = ListCodeListEntries(CodeListId(CodeListKey.of("regions"), importedCatalogId)).query()
            assertThat(entries).extracting<String> { it.code }
                .containsExactlyInAnyOrder("eu", "us", "apac")

            val attr = GetAttributeDefinition(AttributeId(AttributeKey.of("region"), importedCatalogId)).query()
            assertThat(attr).isNotNull()
            assertThat(attr!!.codeListSlug?.value).isEqualTo("regions")
            // Same-catalog binding stays same-catalog after re-import; the
            // wire format's null catalogKey resolves to the importing
            // catalog's own key.
            assertThat(attr.codeListCatalogKey?.value).isEqualTo(result.catalogKey.value)
        }
    }

    @Test
    fun `export records a cross-catalog image's asset as a manifest dependency`() {
        // Regression cover for #555: a template that embeds an image whose asset
        // lives in ANOTHER catalog must declare that asset as a cross-catalog
        // dependency in the exported manifest, so an importer knows to resolve it.
        // An image dependency names its catalog like every other kind. It did not while an
        // image's slug was a generated UUID, unique across every catalog a tenant holds; a
        // readable slug is not, so the catalog is what makes the reference mean one thing.
        val tenant = createTenant("CrossCatalogAssetExport")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val sourceKey = CatalogKey.of("xcat-asset-source")
        val sourceCatalogId = CatalogId(sourceKey, tenantId)
        val assetCatalogKey = CatalogKey.of("xcat-asset-store")

        val zipBytes: ByteArray
        val assetId: String

        val exported = withMediator {
            CreateCatalog(tenantKey = tenantKey, id = sourceKey, name = "Cross-Catalog Asset Source").execute()
            CreateCatalog(tenantKey = tenantKey, id = assetCatalogKey, name = "Cross-Catalog Asset Store").execute()
            declareDependency(tenantKey, sourceKey, on = assetCatalogKey)

            // The asset lives in the OTHER catalog.
            val asset = UploadAsset(
                tenantId = tenantKey,
                name = "shared-logo",
                mediaType = AssetMediaType.PNG,
                content = createMinimalPng(),
                width = 1,
                height = 1,
                catalogKey = assetCatalogKey,
            ).execute()

            // A template in the source catalog references that asset, qualified by
            // its owning catalog (the cross-catalog reference shape the picker writes).
            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, sourceCatalogId)
            CreateDocumentTemplate(id = templateId, name = "Cross-Catalog Image Template").execute().withRequiredDataExample()
            val variantKey = VariantKey.INITIAL
            val variantId = VariantId(variantKey, templateId)
            UpdateVariantContent(
                variantId = variantId,
                templateModel = templateWithCrossCatalogImage(asset.id.value, assetCatalogKey.value),
            ).execute()
            MarkVariantReady(variantId).execute()

            ExportCatalogZip(tenantKey = tenantKey, catalogKey = sourceKey).execute() to asset.id.value
        }
        zipBytes = exported.first.zipBytes
        assetId = exported.second

        val deps = readManifestDependencies(zipBytes)
        assertThat(deps).anySatisfy { dep ->
            assertThat(dep.get("type").asString()).isEqualTo("image")
            assertThat(dep.get("key").asString()).isEqualTo(assetId)
        }
    }

    @Test
    fun `import fails clearly when cross-catalog code-list dependency is missing`() {
        val tenant = createTenant("MissingDep")

        // Build a minimal manifest declaring a dependency on a code list in a
        // catalog the tenant does NOT have. We construct the zip by hand so
        // we don't have to fabricate a whole valid resource graph.
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("catalog.json"))
            zip.write(
                """
                {
                  "schemaVersion": $CATALOG_SCHEMA_VERSION,
                  "catalog": {
                    "registry": "exchange.epistola.app",
                    "namespace": "epistola-tests",
                    "key": "needs-missing",
                    "name": "Needs Missing Dep",
                    "description": "Declares a cross-catalog code-list dep that the tenant does not have."
                  },
                  "publisher": { "name": "Epistola tests" },
                  "release": { "version": "1.0.0", "releasedAt": "2026-05-11T00:00:00Z" },
                  "resources": [],
                  "dependencies": [
                    {
                      "type": "codeList",
                      "registry": "exchange.epistola.app",
                      "namespace": "epistola-tests",
                      "catalogKey": "no-such-catalog",
                      "key": "no-such-list"
                    }
                  ],
                  "catalogDependencies": [
                    {
                      "registry": "exchange.epistola.app",
                      "namespace": "epistola-tests",
                      "catalogKey": "no-such-catalog",
                      "version": "*",
                      "releaseVersion": null,
                      "releaseFingerprint": null
                    }
                  ]
                }
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }

        withMediator {
            assertThatThrownBy {
                CatalogImportContext.runAsImport {
                    ImportCatalogZip(
                        tenantKey = tenant.id,
                        zipBytes = baos.toByteArray(),
                        catalogType = CatalogType.AUTHORED,
                    ).execute()
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("unmet dependencies")
                .hasMessageContaining("code list 'no-such-list'")
                .hasMessageContaining("from catalog 'no-such-catalog'")
        }
    }

    @Test
    fun `export stencils carries each stencil's working copy, whether it reads the template's data included`() {
        val tenant = createTenant("Stencil Export")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-export")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Stencil Export").execute()
            val plainId = StencilId(StencilKey.of("plain"), catalogId)
            CreateStencil(id = plainId, name = "Plain", content = stencilContentWithRoot("first-root")).execute()
            UpdateStencil(plainId, content = stencilContentWithRoot("edited-root")).execute()
            CreateStencil(
                id = StencilId(StencilKey.of("reader"), catalogId),
                name = "Reader",
                content = stencilContentWithRoot("reader-root"),
                readsTemplateData = true,
            ).execute()

            val exported = ExportStencils(tenantKey = tenant.id, catalogKey = catalogKey).query().associateBy { it.key }

            assertThat(exported.keys).containsExactlyInAnyOrder("plain", "reader")
            assertThat(exported.getValue("plain").content.root).isEqualTo("edited-root")
            assertThat(exported.getValue("plain").readsTemplateData).isFalse()
            assertThat(exported.getValue("reader").readsTemplateData).isTrue()
        }
    }

    @Test
    fun `importing a stencil installs it as the working copy, and a re-import replaces it`() {
        val tenant = createTenant("Stencil Import")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-import")
        val stencilId = StencilId(StencilKey.of("imported"), CatalogId(catalogKey, tenantId))
        fun import(root: String, readsTemplateData: Boolean = false) = ImportStencil(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = "imported",
            name = "Imported",
            content = stencilContentWithRoot(root),
            readsTemplateData = readsTemplateData,
        ).execute()

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Stencil Import").execute()

            assertThat(import("first-root")).isEqualTo(InstallStatus.INSTALLED)
            assertThat(GetStencil(stencilId).query()!!.content.root).isEqualTo("first-root")

            // Local edits are replaced by the next import: the archive is the source.
            UpdateStencil(stencilId, content = stencilContentWithRoot("local-root")).execute()
            assertThat(import("second-root", readsTemplateData = true)).isEqualTo(InstallStatus.UPDATED)

            val stored = GetStencil(stencilId).query()!!
            assertThat(stored.content.root).isEqualTo("second-root")
            assertThat(stored.readsTemplateData).isTrue()
        }
    }

    @Test
    fun `round-trip preserves stencil parameter schema and template parameter bindings`() {
        // Issue #383: a parametrised stencil and a template that binds to it must
        // survive a full catalog export → import into a clean tenant — the stencil
        // version's parameter_schema and the consuming node's binding props
        // (parameterBindings / paramsAlias / parameterSchemaSnapshot) all round-trip,
        // and the imported template still renders.
        val source = createTenant("Stencil Params Source")
        val sourceTenantId = TenantId(source.id)
        val catalogKey = CatalogKey.of("stencil-params-roundtrip")
        val sourceCatalogId = CatalogId(catalogKey, sourceTenantId)

        val parameterSchema = ObjectMapper().readValue(
            """{"type":"object","properties":{"recipientName":{"type":"string"}},"required":["recipientName"]}""",
            ObjectNode::class.java,
        )

        val templateKey = TestIdHelpers.nextTemplateId()
        val variantKey = VariantKey.INITIAL

        val zipBytes = withMediator {
            CreateCatalog(tenantKey = source.id, id = catalogKey, name = "Stencil Params Round-Trip").execute()

            // Parametrised stencil.
            val stencilKey = StencilKey.of("greeting")
            val stencilId = StencilId(stencilKey, sourceCatalogId)
            CreateStencil(
                id = stencilId,
                name = "Greeting",
                content = stencilContentWithRoot("greeting-root"),
                parameterSchema = parameterSchema,
            ).execute()

            // Template embedding the stencil with a binding + snapshot, published.
            val templateId = TemplateId(templateKey, sourceCatalogId)
            CreateDocumentTemplate(id = templateId, name = "Letter").execute().withRequiredDataExample()
            val variantId = VariantId(variantKey, templateId)
            UpdateVariantContent(
                variantId = variantId,
                templateModel = templateEmbeddingParametrisedStencil(stencilSlug = stencilKey.value),
            ).execute()
            MarkVariantReady(variantId).execute()

            ExportCatalogZip(tenantKey = source.id, catalogKey = catalogKey).execute().zipBytes
        }

        // Import into a clean tenant — the catalog does not exist there yet.
        val target = createTenant("Stencil Params Target")
        val targetTenantId = TenantId(target.id)
        val targetCatalogId = CatalogId(catalogKey, targetTenantId)

        withMediator {
            val importResult = ImportCatalogZip(
                tenantKey = target.id,
                zipBytes = zipBytes,
                catalogType = CatalogType.AUTHORED,
            ).execute()
            assertThat(importResult.results).allSatisfy { result ->
                assertThat(result.status).isNotEqualTo(InstallStatus.FAILED)
            }

            // 1. The stencil's parameter_schema survived export + import.
            assertThat(GetStencil(StencilId(StencilKey.of("greeting"), targetCatalogId)).query()!!.parameterSchema).isEqualTo(parameterSchema)

            // 2. The consuming template's stencil node kept its parameter binding props.
            val imported = GetVariantContent(
                variantId = VariantId(variantKey, TemplateId(templateKey, targetCatalogId)),
            ).query()
            assertThat(imported).isNotNull
            val stencilNode = imported!!.nodes.values.single { it.type == "stencil" }
            assertThat(stencilNode.props?.get("parameterBindings"))
                .isEqualTo(mapOf("recipientName" to "'Alice'"))
            assertThat(stencilNode.props?.get("paramsAlias")).isEqualTo("params")
            // The schema is the stencil's, read when the instance expands; the stored form keeps no copy.
            assertThat(stencilNode.props).doesNotContainKey("parameterSchemaSnapshot")

            // 3. The imported template renders end-to-end with its bound parameter.
            //    (Text-level parameter rendering is asserted by StencilParameterRenderTest
            //    in :modules:generation; here we prove the imported artifacts render.)
            val pdf = app.epistola.suite.documents.queries.PreviewVariant(
                tenantId = target.id,
                catalogKey = catalogKey,
                templateId = templateKey,
                variantId = variantKey,
                data = ObjectMapper().createObjectNode(),
            ).query()
            assertThat(pdf).isNotEmpty()
            assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
        }
    }

    @Test
    fun `re-importing a stencil with a different parameter schema is an update, not a skip`() {
        // Issue #383 idempotency probe: the parameter schema is part of what a stencil is. A re-import
        // with byte-identical content but a different schema must update, not skip.
        val tenant = createTenant("Stencil Schema Change")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("schema-change")
        val content = stencilContentWithRoot("schema-root")
        val schemaA: Map<String, Any?> = mapOf("type" to "object", "properties" to mapOf("a" to mapOf("type" to "string")))
        val schemaB: Map<String, Any?> = mapOf("type" to "object", "properties" to mapOf("b" to mapOf("type" to "string")))

        fun importWidget(parameterSchema: Map<String, Any?>?) = ImportStencil(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = "widget",
            name = "Widget",
            content = content,
            parameterSchema = parameterSchema,
        ).execute()

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Schema Change").execute()

            assertThat(importWidget(schemaA)).isEqualTo(InstallStatus.INSTALLED)
            assertThat(importWidget(schemaA)).isEqualTo(InstallStatus.SKIPPED)
            assertThat(importWidget(schemaB)).isEqualTo(InstallStatus.UPDATED)
            assertThat(importWidget(null)).isEqualTo(InstallStatus.UPDATED)
        }
    }

    /**
     * A template that embeds the parametrised "greeting" stencil: a stencil node
     * carrying the schema snapshot + a binding for `recipientName`, whose slot holds
     * a text body rendering `{{ params.recipientName }}`. Mirrors the in-template
     * shape produced by inserting a parametrised stencil through the editor.
     */
    private fun templateEmbeddingParametrisedStencil(stencilSlug: String): TemplateDocument {
        val schemaSnapshot = mapOf(
            "type" to "object",
            "properties" to mapOf("recipientName" to mapOf("type" to "string")),
            "required" to listOf("recipientName"),
        )
        return TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "stencil1" to Node(
                    id = "stencil1",
                    type = "stencil",
                    slots = listOf("stencil1-slot"),
                    props = mapOf(
                        "stencilKey" to stencilSlug,
                        "parameterSchemaSnapshot" to schemaSnapshot,
                        "parameterBindings" to mapOf("recipientName" to "'Alice'"),
                        "paramsAlias" to "params",
                    ),
                ),
                "body" to Node(
                    id = "body",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf(
                                            "type" to "expression",
                                            "attrs" to mapOf("expression" to "params.recipientName"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil1")),
                "stencil1-slot" to Slot(id = "stencil1-slot", nodeId = "stencil1", name = "children", children = listOf("body")),
            ),
            themeRef = ThemeRef.Inherit,
        )
    }

    /**
     * A template whose single image node references an asset in another catalog,
     * qualified by that catalog's key (the cross-catalog reference the picker writes).
     */
    private fun templateWithCrossCatalogImage(assetId: String, catalogKey: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "image1" to Node(
                id = "image1",
                type = "image",
                slots = emptyList(),
                props = mapOf("imageKey" to assetId, "catalogKey" to catalogKey),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("image1")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    /** Reads the `dependencies` array from the `catalog.json` inside an export ZIP. */
    private fun readManifestDependencies(zipBytes: ByteArray): List<JsonNode> {
        ZipInputStream(zipBytes.inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name == "catalog.json") {
                    val root = ObjectMapper().readTree(zin.readBytes())
                    return root.get("dependencies")?.toList() ?: emptyList()
                }
                entry = zin.nextEntry
            }
        }
        return emptyList()
    }

    private fun stencilContentWithRoot(rootId: String): TemplateDocument {
        val slotId = "slot-$rootId"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(
                rootId to Node(id = rootId, type = "root", slots = listOf(slotId)),
            ),
            slots = mapOf(
                slotId to Slot(id = slotId, nodeId = rootId, name = "children", children = emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )
    }

    /**
     * Creates a minimal valid 1x1 PNG image (67 bytes).
     */
    private fun createMinimalPng(): ByteArray {
        val header = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A, // PNG signature
        )
        val ihdr = byteArrayOf(
            0x00, 0x00, 0x00, 0x0D, // chunk length = 13
            0x49, 0x48, 0x44, 0x52, // "IHDR"
            0x00, 0x00, 0x00, 0x01, // width = 1
            0x00, 0x00, 0x00, 0x01, // height = 1
            0x08, 0x02, // bit depth = 8, color type = 2 (RGB)
            0x00, 0x00, 0x00, // compression, filter, interlace
            0x90.toByte(), 0x77, 0x53, 0xDE.toByte(), // CRC
        )
        val idat = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C, // chunk length = 12
            0x49, 0x44, 0x41, 0x54, // "IDAT"
            0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
            0x00, 0x02, 0x00, 0x01, // compressed data
            0xE2.toByte(), 0x21, 0xBC.toByte(), 0x33, // CRC
        )
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // chunk length = 0
            0x49, 0x45, 0x4E, 0x44, // "IEND"
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(), // CRC
        )
        return header + ihdr + idat + iend
    }

    /**
     * Creates a ZIP archive that does NOT contain catalog.json.
     */
    private fun createZipWithoutManifest(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("some-other-file.txt"))
            zip.write("not a catalog".toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }
}
