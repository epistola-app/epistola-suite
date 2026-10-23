// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mcp.dto.NamespaceNameInfo
import app.epistola.suite.mcp.tools.CatalogMcpTools
import app.epistola.suite.mcp.tools.ImageMcpTools
import app.epistola.suite.mcp.tools.StencilMcpTools
import app.epistola.suite.mcp.tools.TemplateMcpTools
import app.epistola.suite.mcp.tools.ThemeMcpTools
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Catalog handling over MCP: an assistant starting in a fresh tenant can create the catalog it
 * builds in, and a list filtered by a catalog that does not exist says so instead of looking empty.
 */
class McpCatalogToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var catalogTools: CatalogMcpTools

    @Autowired private lateinit var templateTools: TemplateMcpTools

    @Autowired private lateinit var themeTools: ThemeMcpTools

    @Autowired private lateinit var stencilTools: StencilMcpTools

    @Autowired private lateinit var imageTools: ImageMcpTools

    /** Like the API-key filter: a tenant-scoped service principal whose `users` row exists. */
    private fun <T> runAsApiKey(
        tenantId: TenantId,
        roles: Set<TenantRole> = TenantRole.entries.toSet(),
        block: () -> T,
    ): T = UUID.randomUUID().let { keyId ->
        runAs(
            EpistolaPrincipal(
                userId = UserKey.of(keyId),
                externalId = "apikey:$keyId",
                email = "apikey-$keyId@npa.epistola",
                displayName = "Test API Key",
                tenantMemberships = mapOf(tenantId.key to roles),
                globalRoles = emptySet(),
                platformRoles = emptySet(),
                currentTenantId = tenantId.key,
            ),
            block,
        )
    }

    private fun newTenant(): TenantId = TenantId(createTenant("MCP Catalogs ${UUID.randomUUID()}").id)

    @Test
    fun `create_catalog creates a catalog that can be changed here, which the write tools build in`() {
        val tenantId = newTenant()

        val catalog = runAsApiKey(tenantId) { catalogTools.createCatalog("sociaal-domein", "Sociaal domein", "Brieven") }

        assertThat(catalog.id).isEqualTo("sociaal-domein")
        assertThat(catalog.editable).isTrue
        assertThat(catalog.readOnlyReason).isNull()
        assertThat(catalog.description).isEqualTo("Brieven")
        assertThat(runAsApiKey(tenantId) { catalogTools.listCatalogs() }.map { it.id }).contains("sociaal-domein")

        runAsApiKey(tenantId) { templateTools.createTemplate("sociaal-domein", "uitnodiging", "Uitnodiging", null, null, null) }
        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates("sociaal-domein", null) }.map { it.id })
            .containsExactly("uitnodiging")
    }

    @Test
    fun `list_catalogs says which catalogs can be changed here, why not, and the highest release received or released here`() {
        val author = createTenant("MCP Catalogs Author ${UUID.randomUUID()}").id
        val receiver = newTenant()
        val catalog = CatalogKey.of("received")
        val brand = { tenant: TenantKey -> ThemeId(ThemeKey.of("brand"), CatalogId(catalog, TenantId(tenant))) }
        withMediator {
            CreateCatalog(tenantKey = author, id = catalog, name = "Received").execute()
            CreateTheme(id = brand(author), name = "Brand").execute()
            MarkCatalogReady(author, catalog).execute()
            ReleaseCatalogDraft(tenantKey = author, catalogKey = catalog, version = "1.0.0").execute()
            val archive = ExportCatalogZip(author, catalog, "1.0.0").execute().zipBytes
            // Received from a file, then changed and released here: it is a catalog like any other.
            ImportCatalogZip(receiver.key, archive).execute()
            UpdateTheme(brand(receiver.key), name = "Brand, ours").execute()
            MarkCatalogReady(receiver.key, catalog).execute()
            // Releasing it here needs its namespace: the two tenants share it.
            TestNamespaces.share(author, receiver.key).execute()
            ReleaseCatalogDraft(tenantKey = receiver.key, catalogKey = catalog, version = "1.0.1").execute()
        }

        val catalogs = runAsApiKey(receiver) { catalogTools.listCatalogs() }.associateBy { it.id }

        val received = catalogs.getValue("received")
        assertThat(received.editable).isTrue
        assertThat(received.readOnlyReason).isNull()
        assertThat(received.releasedVersion).`as`("released here above what it received").isEqualTo("1.0.1")
        val system = catalogs.getValue(SYSTEM_CATALOG_KEY.value)
        assertThat(system.editable).isFalse
        assertThat(system.readOnlyReason).contains("Epistola supplies this catalog")
        assertThat(system.source).startsWith("classpath:")
    }

    @Test
    fun `create_catalog names a catalog in a namespace the tenant holds or under local, and list_namespaces says which`() {
        val tenantId = newTenant()
        val own = TestNamespaces.of(tenantId.key)
        withMediator { TestNamespaces.grant(tenantId.key, "acme", authority = "gemeente-x.nl").execute() }

        val namespaces = runAsApiKey(tenantId) { catalogTools.listNamespaces() }
        assertThat(namespaces.held).containsExactly(NamespaceNameInfo("gemeente-x.nl", "acme"), NamespaceNameInfo(own.authority, own.namespace))
        assertThat(namespaces.default).`as`("several, none preferred").isNull()

        assertThatThrownBy { runAsApiKey(tenantId) { catalogTools.createCatalog("brieven", "Brieven", null) } }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_REQUIRED)
                assertThat(it.message).contains("holds several namespaces")
            }
        val held = runAsApiKey(tenantId) { catalogTools.createCatalog("brieven", "Brieven", null, namespace = "acme") }
        assertThat(held.authority to held.namespace).isEqualTo("gemeente-x.nl" to "acme")

        val local = runAsApiKey(tenantId) { catalogTools.createCatalog("notities", "Notities", null, namespace = "personnel", authority = "local") }
        assertThat(local.authority to local.namespace).isEqualTo("local" to "personnel")
        assertThat(runAsApiKey(tenantId) { catalogTools.listNamespaces() }.localInUse).containsExactly("personnel")

        assertThatThrownBy { runAsApiKey(tenantId) { catalogTools.createCatalog("elders", "Elders", null, namespace = "elsewhere") } }
            .hasMessage("This tenant does not hold the namespace elsewhere (exchange.epistola.app); an administrator can grant it.")
    }

    @Test
    fun `two catalogs with one key are told apart by namespace, and the key alone is refused`() {
        val tenantId = newTenant()
        withMediator {
            TestNamespaces.grant(tenantId.key, "acme").execute()
            TestNamespaces.grant(tenantId.key, "bureau").execute()
            SaveFeatureToggle(tenantId.key, KnownFeatures.SHARED_CATALOG_KEYS, true).execute()
        }

        CatalogNames.frame {
            runAsApiKey(tenantId) {
                val acme = catalogTools.createCatalog("letters", "Acme letters", null, namespace = "acme")
                catalogTools.createCatalog("letters", "Bureau letters", null, namespace = "bureau")
                assertThat(acme.address).isEqualTo("acme/letters")
            }
        }
        CatalogNames.frame { runAsApiKey(tenantId) { templateTools.createTemplate("acme/letters", "invoice", "Acme invoice", null, null, null) } }
        CatalogNames.frame { runAsApiKey(tenantId) { templateTools.createTemplate("bureau/letters", "invoice", "Bureau invoice", null, null, null) } }

        assertThat(CatalogNames.frame { runAsApiKey(tenantId) { templateTools.listTemplates("bureau/letters", null) } }.map { it.name }).containsExactly("Bureau invoice")
        assertThat(CatalogNames.frame { runAsApiKey(tenantId) { templateTools.listTemplates("acme/letters", null) } }.map { it.name }).containsExactly("Acme invoice")
        assertThatThrownBy { CatalogNames.frame { runAsApiKey(tenantId) { templateTools.listTemplates("letters", null) } } }
            .hasMessage("The catalog key letters names several catalogs of this tenant (acme/letters, bureau/letters): name its namespace.")
    }

    @Test
    fun `create_catalog refuses a key that is taken`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { catalogTools.createCatalog("brieven", "Brieven", null) }

        assertThatThrownBy { runAsApiKey(tenantId) { catalogTools.createCatalog("brieven", "Other", null) } }
            .hasMessageContaining("brieven")
    }

    @Test
    fun `create_catalog needs the CATALOG_MANAGE permission`() {
        val tenantId = newTenant()

        assertThatThrownBy {
            runAsApiKey(tenantId, roles = setOf(TenantRole.CONTENT_AUTHOR, TenantRole.CONTENT_PUBLISHER)) {
                catalogTools.createCatalog("brieven", "Brieven", null)
            }
        }.hasMessageContaining("CATALOG_MANAGE")
        assertThat(runAsApiKey(tenantId) { catalogTools.listCatalogs() }.map { it.id }).doesNotContain("brieven")
    }

    @Test
    fun `list tools reject a catalog that does not exist instead of returning an empty list`() {
        val tenantId = newTenant()
        val listings = mapOf<String, () -> List<Any>>(
            "list_templates" to { templateTools.listTemplates("sociaaldomein", null) },
            "list_themes" to { themeTools.listThemes("sociaaldomein", null) },
            "list_stencils" to { stencilTools.listStencils("sociaaldomein", null, null) },
            "list_images" to { imageTools.listImages("sociaaldomein", null) },
        )

        listings.forEach { (tool, call) ->
            assertThatThrownBy { runAsApiKey(tenantId) { call() } }
                .describedAs(tool)
                .isInstanceOf(NoSuchElementException::class.java)
                .hasMessageContaining("Catalog 'sociaaldomein' not found")
                .hasMessageContaining("list_catalogs")
        }
    }

    @Test
    fun `list tools still return an empty list for an existing empty catalog, and all catalogs when none is given`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { catalogTools.createCatalog("leeg", "Leeg", null) }

        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates("leeg", null) }).isEmpty()
        assertThat(runAsApiKey(tenantId) { themeTools.listThemes("leeg", null) }).isEmpty()
        assertThat(runAsApiKey(tenantId) { stencilTools.listStencils("leeg", null, null) }).isEmpty()
        assertThat(runAsApiKey(tenantId) { imageTools.listImages("leeg", null) }).isEmpty()
        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates(null, null) }).isNotNull
        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates("", null) }).isNotNull
    }
}
