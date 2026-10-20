// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.ImportStatus
import app.epistola.suite.catalog.commands.ImportTemplateInput
import app.epistola.suite.catalog.commands.ImportTemplates
import app.epistola.suite.catalog.commands.ImportVariantInput
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import app.epistola.catalog.canonical.CatalogCanonicalizer as PortableCatalogCanonicalizer

/**
 * JavaScript expressions are no longer evaluated, and the template model still lists `javascript`
 * as a language. Every write path — editor and API saves, catalog import, MCP authoring — runs
 * through the template validator, so each must refuse such a document instead of storing it.
 */
class JavaScriptExpressionRejectionIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var generationService: GenerationService

    private fun documentWithCondition(language: String) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "cond" to Node(
                id = "cond",
                type = "conditional",
                slots = listOf("cond-slot"),
                props = mapOf("condition" to mapOf("raw" to "show", "language" to language)),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("cond")),
            "cond-slot" to Slot(id = "cond-slot", nodeId = "cond", name = "body", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )

    @Test
    fun `saving a draft with a javascript expression is rejected`() {
        val tenant = createTenant("JS Draft")
        withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "JS Draft").execute()

            val exception = assertThrows<ValidationException> {
                UpdateVariantContent(variantId = VariantId(VariantKey.INITIAL, templateId), templateModel = documentWithCondition("javascript")).execute()
            }

            assertThat(exception.field).isEqualTo("templateModel.nodes.cond.props.condition.language")
            assertThat(exception.code).isEqualTo(ValidationCode.TEMPLATE_EXPRESSION_INVALID)
            assertThat(exception.message).contains("JavaScript expressions are no longer supported")

            // The same document in JSONata is accepted: only the language is refused.
            UpdateVariantContent(variantId = VariantId(VariantKey.INITIAL, templateId), templateModel = documentWithCondition("jsonata")).execute()
        }
    }

    @Test
    fun `creating a stencil with a javascript expression is rejected`() {
        val tenant = createTenant("JS Stencil")
        withMediator {
            val exception = assertThrows<ValidationException> {
                CreateStencil(
                    id = StencilId(StencilKey.of("js-stencil"), CatalogId.default(TenantId(tenant.id))),
                    name = "JS Stencil",
                    content = documentWithCondition("javascript"),
                ).execute()
            }
            assertThat(exception.field).isEqualTo("content.nodes.cond.props.condition.language")
        }
    }

    @Test
    fun `importing a template with a javascript expression fails that template`() {
        val tenant = createTenant("JS Import")
        withMediator {
            val results = ImportTemplates(
                tenantId = TenantId(tenant.id),
                templates = listOf(
                    ImportTemplateInput(
                        slug = TestIdHelpers.nextTemplateId().value,
                        name = "JS Import",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = documentWithCondition("javascript"),
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                    ),
                ),
                catalogKey = CatalogKey.DEFAULT,
            ).execute()

            assertThat(results.single().status).isEqualTo(ImportStatus.FAILED)
            assertThat(results.single().errorMessage).contains("JavaScript expressions are no longer supported")
        }
    }

    @Test
    fun `a catalog ZIP whose template uses a javascript expression does not install it`() {
        val source = createTenant("JS Zip Source")
        val target = createTenant("JS Zip Target")
        val catalogKey = CatalogKey.of("js-zip-catalog")

        val exported = withMediator {
            CreateCatalog(tenantKey = source.id, id = catalogKey, name = "JS Zip").execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(catalogKey, TenantId(source.id)))
            CreateDocumentTemplate(id = templateId, name = "JS Zip Template").execute().withRequiredDataExample()
            val variantId = VariantId(VariantKey.INITIAL, templateId)
            UpdateVariantContent(variantId = variantId, templateModel = documentWithCondition("jsonata")).execute()
            MarkVariantReady(variantId).execute()
            mediator.exportReleased(app.epistola.suite.common.ids.CatalogId(catalogKey, app.epistola.suite.common.ids.TenantId(source.id))).zipBytes
        }

        // A hand-edited archive: the publisher switched the expression to JavaScript.
        val entries = unzip(exported)
        val templateEntries = entries.keys.filter { it.startsWith("resources/template/") }
        assertThat(templateEntries).isNotEmpty()
        templateEntries.forEach { name ->
            val json = entries.getValue(name).decodeToString()
            assertThat(json).contains("\"language\":\"jsonata\"")
            entries[name] = json.replace("\"language\":\"jsonata\"", "\"language\":\"javascript\"").toByteArray()
        }

        // Installing a release is all or nothing: the template is refused, and so is the release.
        assertThatThrownBy {
            withMediator { ImportCatalogZip(tenantKey = target.id, zipBytes = rezip(entries), catalogType = CatalogType.AUTHORED).execute() }
        }.hasMessageContaining("JavaScript expressions are no longer supported")
        assertThat(withMediator { GetCatalog(target.id, catalogKey).query() }).isNull()
    }

    @Test
    fun `a stored template with a javascript expression is found by the operator query and fails to render`() {
        val tenant = createTenant("JS Stored")
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        withMediator {
            CreateDocumentTemplate(id = templateId, name = "JS Stored").execute()
            UpdateVariantContent(variantId = variantId, templateModel = documentWithCondition("jsonata")).execute()
        }

        // Raw SQL on purpose: every command now refuses a JavaScript expression, and this is the
        // state a database written by an older release can still hold.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE template_variants
                SET template_model = jsonb_set(template_model, '{nodes,cond,props,condition,language}', '"javascript"')
                WHERE tenant_key = :tenantKey AND template_model #> '{nodes,cond}' IS NOT NULL
                """,
            ).bind("tenantKey", tenant.id).execute()
        }

        // The query the operator guide (docs/generation.md) gives finds it…
        val affected = jdbi.withHandle<List<Map<String, Any>>, Exception> { handle ->
            handle.createQuery(OPERATOR_TEMPLATE_QUERY).mapToMap().list()
        }.filter { it["tenant_key"].toString() == tenant.id.value }
        assertThat(affected).singleElement().satisfies({ row ->
            assertThat(row["template"].toString()).isEqualTo(templateId.key.value)
            assertThat(row["variant"].toString()).isEqualTo(VariantKey.INITIAL.value)
        })

        // …the document still loads, so it can be opened and rewritten in JSONata…
        val stored = withMediator { GetEditorContext(variantId).query() }!!.templateModel

        // …and rendering it fails with the explanation instead of evaluating it.
        val exception = assertThrows<ValidationException> {
            withMediator { generationService.renderPdfWithoutTheme(stored, emptyMap(), ByteArrayOutputStream()) }
        }
        assertThat(exception.message).contains("JavaScript expressions are no longer supported")
    }

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readAllBytes()
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** Re-packs [entries], recomputing the release fingerprint so only the edited content differs. */
    private fun rezip(entries: Map<String, ByteArray>): ByteArray {
        val refreshed = LinkedHashMap(entries)
        val manifest = objectMapper.readTree(refreshed["catalog.json"]!!) as ObjectNode
        (manifest.get("release") as ObjectNode).remove("fingerprint")
        refreshed["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        val read = CatalogArchiveReader.read(ByteArrayInputStream(rawZip(refreshed)))
        val archive = read.archive ?: return rawZip(refreshed)
        val fingerprint = archive.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }
        (manifest.get("release") as ObjectNode).put("fingerprint", fingerprint)
        refreshed["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        return rawZip(refreshed)
    }

    private fun rawZip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        /** Verbatim from the operator note in docs/generation.md — keep the two in step. */
        private const val OPERATOR_TEMPLATE_QUERY = """
            SELECT t.tenant_key, c.catalog_key, t.id AS template, v.id AS variant
            FROM template_variants v
            JOIN document_templates t
              ON t.tenant_key = v.tenant_key AND t.resource_id = v.template_resource_id
            JOIN catalogs c
              ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
            WHERE jsonb_path_exists(v.template_model, '$.** ? (@.language like_regex "^\\s*javascript\\s*$" flag "i")')
            ORDER BY 1, 2, 3, 4
        """
    }
}
