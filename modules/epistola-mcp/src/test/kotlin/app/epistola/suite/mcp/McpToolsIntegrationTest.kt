// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mcp.tools.CatalogMcpTools
import app.epistola.suite.mcp.tools.PreviewMcpTools
import app.epistola.suite.mcp.tools.TemplateMcpTools
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

class McpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var catalogMcpTools: CatalogMcpTools

    @Autowired
    private lateinit var templateMcpTools: TemplateMcpTools

    @Autowired
    private lateinit var previewMcpTools: PreviewMcpTools

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /**
     * MCP tools resolve the tenant from the API key via [SecurityContext.current].
     * Tests bind a tenant-scoped principal so the tools see a real tenant.
     */
    private fun <T> runAsApiKey(tenantId: TenantId, block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        val tenantPrincipal = EpistolaPrincipal(
            userId = UserKey.of(UUID.randomUUID()),
            externalId = "apikey-test",
            email = "test@apikey.example",
            displayName = "Test API Key",
            tenantMemberships = mapOf(tenantId.key to TenantRole.entries.toSet()),
            globalRoles = emptySet(),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = tenantId.key,
        )
        SecurityContext.runWithPrincipal(tenantPrincipal, block)
    }

    @Test
    fun `list_catalogs returns the default catalog for the API key tenant`() {
        val tenant = createTenant("MCP Test Tenant")
        val tenantId = TenantId(tenant.id)

        val catalogs = runAsApiKey(tenantId) { catalogMcpTools.listCatalogs() }

        assertThat(catalogs).isNotEmpty
        assertThat(catalogs.map { it.id }).contains("default")
    }

    @Test
    fun `list_templates returns templates seeded in the tenant`() {
        val tenant = createTenant("MCP Templates Tenant")
        val tenantId = TenantId(tenant.id)
        val templateKey = withMediator {
            val id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
            CreateDocumentTemplate(id = id, name = "Welcome letter").execute()
            id.key
        }

        val templates = runAsApiKey(tenantId) {
            templateMcpTools.listTemplates(catalogId = "default", search = null)
        }

        assertThat(templates).hasSize(1)
        assertThat(templates[0].id).isEqualTo(templateKey.value)
        assertThat(templates[0].name).isEqualTo("Welcome letter")
    }

    @Test
    fun `get_template returns metadata for an existing template`() {
        val tenant = createTenant("MCP Get Template Tenant")
        val tenantId = TenantId(tenant.id)
        val templateKey = withMediator {
            val id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
            CreateDocumentTemplate(id = id, name = "Invoice").execute()
            id.key
        }

        val info = runAsApiKey(tenantId) {
            templateMcpTools.getTemplate(catalogId = "default", templateId = templateKey.value)
        }

        assertThat(info).isNotNull
        assertThat(info!!.id).isEqualTo(templateKey.value)
        assertThat(info.name).isEqualTo("Invoice")
        assertThat(info.catalogId).isEqualTo("default")
    }

    @Test
    fun `get_template_content returns editor context for a variant`() {
        val tenant = createTenant("MCP Editor Context Tenant")
        val tenantId = TenantId(tenant.id)

        // Seed: template + variant + draft content + contract with example
        val (templateKey, variantKey) = withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
            CreateDocumentTemplate(id = templateId, name = "Letter").execute()

            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(
                id = variantId,
                title = "English",
                description = null,
                attributes = emptyMap(),
            ).execute()

            UpdateDraft(
                variantId = variantId,
                templateModel = simpleTemplateDocument(),
            ).execute()

            UpdateContractVersion(
                templateId = templateId,
                schema = null,
                dataModel = objectMapper.readTree("""{"type":"object","properties":{"name":{"type":"string"}}}""") as ObjectNode,
                dataExamples = listOf(
                    DataExample(
                        id = UUID.randomUUID().toString(),
                        name = "default",
                        data = objectMapper.readTree("""{"name":"World"}""") as ObjectNode,
                    ),
                ),
                forceUpdate = true,
            ).execute()

            templateId.key to variantId.key
        }

        val content = runAsApiKey(tenantId) {
            templateMcpTools.getTemplateContent(
                catalogId = "default",
                templateId = templateKey.value,
                variantId = variantKey.value,
            )
        }

        assertThat(content).isNotNull
        assertThat(content!!.templateName).isEqualTo("Letter")
        assertThat(content.variantAttributes).isEmpty()
        assertThat(content.dataExamples).hasSize(1)
        assertThat(content.dataExamples[0].name).isEqualTo("default")
        assertThat(content.dataModel).isNotNull
    }

    @Test
    fun `tools refuse to operate when the principal has no tenant`() {
        val tenant = createTenant("MCP No-Tenant Tenant")
        val tenantId = TenantId(tenant.id)
        withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
            CreateDocumentTemplate(id = templateId, name = "Invisible").execute()
        }

        // Bind a principal with currentTenantId = null
        val rootlessPrincipal = EpistolaPrincipal(
            userId = UserKey.of(UUID.randomUUID()),
            externalId = "no-tenant",
            email = "no-tenant@example.com",
            displayName = "No Tenant",
            tenantMemberships = emptyMap(),
            currentTenantId = null,
        )

        val ex = runCatching {
            MediatorContext.runWithMediator(mediator) {
                SecurityContext.runWithPrincipal(rootlessPrincipal) {
                    catalogMcpTools.listCatalogs()
                }
            }
        }.exceptionOrNull()

        assertThat(ex).isNotNull
        assertThat(ex).hasMessageContaining("no tenant scope")
    }

    private fun simpleTemplateDocument(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
            "text1" to Node(id = "text1", type = "text", slots = emptyList(), props = mapOf("content" to "Hello {{name}}")),
        ),
        slots = mapOf(
            "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("text1")),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
