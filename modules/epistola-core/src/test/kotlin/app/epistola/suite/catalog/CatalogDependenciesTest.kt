// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ResolveCatalogDependencies
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.generation.release.ReleaseRef
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.zip.ZipInputStream

/**
 * Declared catalog dependencies (contract ADR 0003): a catalog declares each catalog it depends on
 * once with an npm range, each declaration is locked to one release like a lockfile, and a release
 * is cut with, records and renders with exactly those releases.
 */
class CatalogDependenciesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var source: ReleaseRenderSource

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** `shared` with a brand theme released as each of [releases], and an empty `letters`. */
    private fun setUp(name: String, vararg releases: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant))), "Brand", documentStyles = mapOf("color" to "#000000")).execute()
        releases.forEach { ReleaseCatalogVersion(tenant, shared, it).execute() }
        tenant
    }

    private fun declared(tenant: TenantKey, version: String) = withMediator {
        val target = GetCatalog(tenant, shared).query()!!
        DeclaredCatalogDependency(target.registry, target.namespace, shared.value, version)
    }

    private fun update(tenant: TenantKey, vararg dependencies: DeclaredCatalogDependency) = withMediator {
        UpdateCatalogDependencies(tenant, letters, dependencies.toList()).execute()
    }

    private fun releaseShared(tenant: TenantKey, version: String, color: String) = withMediator {
        UpdateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant))), documentStyles = mapOf("color" to color)).execute()
        ReleaseCatalogVersion(tenant, shared, version).execute()
    }

    /** An invoice in `letters` using shared's brand theme, published. */
    private fun invoiceOnSharedTheme(tenant: TenantKey) = withMediator {
        val templateId = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant)))
        CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute()
        val variant = VariantId(VariantKey.INITIAL, templateId)
        PublishVersion(VersionId(UpdateDraft(variant, plainModel()).execute()!!.id, variant)).execute()
    }

    // ── Declaring ──

    @Test
    fun `a declaration is locked to the highest release its selector matches`() {
        val tenant = setUp("deps-lock", "1.0.0", "1.1.0", "2.0.0")

        val locked = update(tenant, declared(tenant, "^1.0.0")).single()

        assertThat(locked.releaseVersion).isEqualTo("1.1.0")
        assertThat(locked.releaseFingerprint).startsWith("v5:")
        assertThat(withMediator { ListCatalogDependencies(tenant, letters).query() }).containsExactly(locked)
    }

    @Test
    fun `a lock stays where it is when the dependency releases, until resolved again`() {
        val tenant = setUp("deps-stay", "1.0.0")
        update(tenant, declared(tenant, "^1.0.0"))
        releaseShared(tenant, "1.1.0", "#111111")

        assertThat(withMediator { ListCatalogDependencies(tenant, letters).query() }.single().releaseVersion).isEqualTo("1.0.0")
        // Declaring again with a selector the lock still satisfies keeps it too.
        assertThat(update(tenant, declared(tenant, ">=1.0.0 <3.0.0")).single().releaseVersion).isEqualTo("1.0.0")

        val resolved = withMediator { ResolveCatalogDependencies(tenant, letters).execute() }.single()
        assertThat(resolved.releaseVersion).isEqualTo("1.1.0")
    }

    @Test
    fun `a selector the lock no longer satisfies moves it, and one matching nothing leaves it unlocked`() {
        val tenant = setUp("deps-move", "1.0.0", "1.1.0")
        update(tenant, declared(tenant, "1.0.0"))

        assertThat(update(tenant, declared(tenant, "~1.1.0")).single().releaseVersion).isEqualTo("1.1.0")
        val unlocked = update(tenant, declared(tenant, "^2.0.0")).single()
        assertThat(unlocked.releaseVersion).isNull()
        assertThat(unlocked.releaseFingerprint).isNull()
    }

    @Test
    fun `a declaration is refused for a bad selector, a duplicate, itself or a catalog the tenant does not have`() {
        val tenant = setUp("deps-refused", "1.0.0")
        val good = declared(tenant, "^1.0.0")
        val own = withMediator { GetCatalog(tenant, letters).query()!! }

        fun refused(vararg dependencies: DeclaredCatalogDependency, message: String) = assertThatThrownBy { update(tenant, *dependencies) }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.CATALOG_DEPENDENCY_INVALID)
            .hasMessageContaining(message)

        // Ivy's interval syntax is not the npm range grammar.
        refused(good.copy(version = "[1.0,2.0)"), message = "npm version range")
        refused(good, good.copy(version = "*"), message = "declared more than once")
        refused(DeclaredCatalogDependency(own.registry, own.namespace, letters.value, "*"), message = "cannot depend on itself")
        refused(good.copy(namespace = "elsewhere"), message = "has no catalog")
    }

    @Test
    fun `a dependency renamed to another namespace stays declared, with its lock`() {
        val tenant = setUp("deps-rename", "1.0.0")
        val before = update(tenant, declared(tenant, "^1.0.0")).single()

        withMediator { ChangeCatalogNamespace(tenant, shared, "renamed").execute() }

        val after = withMediator { ListCatalogDependencies(tenant, letters).query() }.single()
        assertThat(after).isEqualTo(before.copy(namespace = "renamed"))
    }

    @Test
    fun `moving a resource another catalog uses into a third declares the dependency it creates`() {
        val tenant = setUp("deps-move-declares", "1.0.0")
        withMediator {
            // letters' invoice uses letters' own theme, which then moves to shared.
            CreateTheme(ThemeId(ThemeKey.of("house"), CatalogId(letters, TenantId(tenant))), "House").execute()
            val templateId = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant)))
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("house"), themeCatalogKey = letters).execute()
            val variant = VariantId(VariantKey.INITIAL, templateId)
            PublishVersion(VersionId(UpdateDraft(variant, plainModel()).execute()!!.id, variant)).execute()
        }
        assertThat(withMediator { ListCatalogDependencies(tenant, letters).query() }).isEmpty()

        val relocations = listOf(ResourceAddress(CatalogResourceType.THEME, letters.value, "house").movedTo(shared))
        withMediator {
            val plan = PreviewCatalogResourceMove(tenant, relocations).query()
            MoveCatalogResources(tenant, relocations, plan.planFingerprint).execute()
        }

        val declared = withMediator { ListCatalogDependencies(tenant, letters).query() }.single()
        assertThat(declared.catalogKey to declared.version).isEqualTo(shared.value to "*")
        assertThat(declared.releaseVersion).isEqualTo("1.0.0")
    }

    // ── Releasing ──

    @Test
    fun `a release records the release each dependency was locked to, and the manifest carries it`() {
        val tenant = setUp("deps-release", "1.0.0")
        update(tenant, declared(tenant, "^1.0.0"))
        invoiceOnSharedTheme(tenant)
        releaseShared(tenant, "1.1.0", "#111111")

        withMediator { ReleaseCatalogVersion(tenant, letters, "1.0.0").execute() }

        val pins = withMediator { ListReleaseDependencies(tenant, letters, "1.0.0").query() }
        assertThat(pins.map { it.catalogKey to it.releaseVersion }).containsExactly(shared.value to "1.0.0")
        val sharedFingerprint = pins.single().releaseFingerprint
        assertThat(sharedFingerprint).isEqualTo(releaseFingerprint(tenant, shared, "1.0.0"))
        val archive = withMediator { ExportCatalogZip(tenant, letters, version = "1.0.0").execute() }.zipBytes
        assertThat(String(entry(archive, "catalog.json"))).contains("\"releaseVersion\":\"1.0.0\"", "\"releaseFingerprint\":\"$sharedFingerprint\"")
    }

    @Test
    fun `a release locks a declaration that had no release to match until now`() {
        val tenant = setUp("deps-late")
        update(tenant, declared(tenant, "^1.0.0"))
        invoiceOnSharedTheme(tenant)
        releaseShared(tenant, "1.0.0", "#111111")

        withMediator { ReleaseCatalogVersion(tenant, letters, "1.0.0").execute() }

        assertThat(withMediator { ListReleaseDependencies(tenant, letters, "1.0.0").query() }.single().releaseVersion).isEqualTo("1.0.0")
        assertThat(withMediator { ListCatalogDependencies(tenant, letters).query() }.single().releaseVersion).isEqualTo("1.0.0")
    }

    @Test
    fun `a release using a catalog it does not declare is refused`() {
        val tenant = setUp("deps-undeclared", "1.0.0")
        invoiceOnSharedTheme(tenant)

        assertThatThrownBy { withMediator { ReleaseCatalogVersion(tenant, letters, "1.0.0").execute() } }
            .hasMessageContaining("which catalogDependencies does not declare")
    }

    // ── Installing ──

    @Test
    fun `an installed catalog keeps the pins its archive was cut with and renders with them`() {
        val origin = setUp("deps-origin", "1.0.0")
        update(origin, declared(origin, "^1.0.0"))
        invoiceOnSharedTheme(origin)
        withMediator { ReleaseCatalogVersion(origin, letters, "1.0.0").execute() }
        releaseShared(origin, "1.1.0", "#ff0000")
        val sharedArchives = listOf("1.0.0", "1.1.0").map { version -> withMediator { ExportCatalogZip(origin, shared, version).execute() }.zipBytes }
        val lettersArchive = withMediator { ExportCatalogZip(origin, letters, "1.0.0").execute() }.zipBytes

        val target = createTenant("deps-target").id
        withMediator {
            sharedArchives.forEach { ImportCatalogZip(target, it, CatalogType.SUBSCRIBED).execute() }
            ImportCatalogZip(target, lettersArchive, CatalogType.SUBSCRIBED).execute()
        }

        val pin = withMediator { ListReleaseDependencies(target, letters, "1.0.0").query() }.single()
        assertThat(pin.releaseVersion).isEqualTo("1.0.0")
        assertThat(pin.releaseFingerprint).isEqualTo(releaseFingerprint(origin, shared, "1.0.0"))
        val tenant = withMediator { GetTenant(target).query()!! }
        val inputs = source.resolve(tenant, ReleaseRef(letters, "1.0.0"), "invoice", VariantKey.INITIAL.value)
        assertThat(inputs.resolvedTheme.documentStyles)
            .`as`("shared@1.0.0 as pinned, though the target installed shared@1.1.0 since")
            .containsEntry("color", "#000000")
    }

    private fun releaseFingerprint(tenant: TenantKey, catalog: CatalogKey, version: String): String = withMediator {
        ListCatalogReleases(tenant, catalog).query().single { it.version == version }.fingerprint
    }

    private fun entry(zip: ByteArray, name: String): ByteArray = ZipInputStream(zip.inputStream()).use { stream ->
        generateSequence { stream.nextEntry }.first { it.name == name }
        stream.readAllBytes()
    }

    private fun plainModel(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("children")),
            "body" to Node(id = "body", type = "text"),
        ),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = listOf("body"))),
        themeRef = ThemeRef.Inherit,
    )
}
