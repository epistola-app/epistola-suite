// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.commands.UpgradeCatalogDependencies
import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.releases.CatalogDependenciesUnreleasedException
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
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
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.testAddress
import app.epistola.suite.testing.testCatalog
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

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** `shared` with a brand theme released as each of [releases], and an empty `letters`. */
    private fun setUp(name: String, vararg releases: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), shared)), "Brand", documentStyles = mapOf("color" to "#000000")).execute()
        releases.forEach {
            MarkCatalogReady(testCatalog(tenant, shared)).execute()
            ReleaseCatalogDraft(testCatalog(tenant, shared), it).execute()
        }
        tenant
    }

    private fun declared(tenant: TenantKey, version: String) = withMediator {
        val target = GetCatalog(testCatalog(tenant, shared)).query()!!
        DeclaredCatalogDependency(target.registry, target.namespace, shared.value, version)
    }

    private fun update(tenant: TenantKey, vararg dependencies: DeclaredCatalogDependency) = withMediator {
        UpdateCatalogDependencies(testCatalog(tenant, letters), dependencies.toList()).execute()
    }

    private fun releaseShared(tenant: TenantKey, version: String, color: String) = withMediator {
        UpdateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), shared)), documentStyles = mapOf("color" to color)).execute()
        MarkCatalogReady(testCatalog(tenant, shared)).execute()
        ReleaseCatalogDraft(testCatalog(tenant, shared), version).execute()
    }

    /** An invoice in `letters` using shared's brand theme, published. */
    private fun invoiceOnSharedTheme(tenant: TenantKey) = withMediator {
        val templateId = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))
        CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(templateId.tenantKey, shared)).execute()
        val variant = VariantId(VariantKey.INITIAL, templateId)
        UpdateVariantContent(variant, plainModel()).execute()
        MarkVariantReady(variant).execute()
    }

    // ── Declaring ──

    @Test
    fun `a declaration pins the exact release it names, with that release's fingerprint`() {
        val tenant = setUp("deps-pin", "1.0.0", "1.1.0", "2.0.0")

        val pinned = update(tenant, declared(tenant, "1.1.0")).single()

        assertThat(pinned.releaseVersion).`as`("the release named, not the highest").isEqualTo("1.1.0")
        assertThat(pinned.releaseFingerprint).isEqualTo(releaseFingerprint(tenant, shared, "1.1.0"))
        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }).containsExactly(pinned)
    }

    @Test
    fun `a pin stays where it is when the dependency releases, until resolved again`() {
        val tenant = setUp("deps-stay", "1.0.0")
        update(tenant, declared(tenant, "1.0.0"))
        releaseShared(tenant, "1.1.0", "#111111")

        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }.single().releaseVersion).isEqualTo("1.0.0")

        val resolved = withMediator { UpgradeCatalogDependencies(testCatalog(tenant, letters)).execute() }.single()
        assertThat(resolved.releaseVersion).isEqualTo("1.1.0")
    }

    @Test
    fun `a declaration of a release the tenant does not have is refused`() {
        val tenant = setUp("deps-missing-release", "1.0.0")

        assertThatThrownBy { update(tenant, declared(tenant, "2.0.0")) }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.CATALOG_DEPENDENCY_INVALID)
            .hasMessageContaining("no release 2.0.0 of shared")
    }

    @Test
    fun `a declaration is refused for a range, a duplicate, itself or a catalog the tenant does not have`() {
        val tenant = setUp("deps-refused", "1.0.0")
        val good = declared(tenant, "1.0.0")
        val own = withMediator { GetCatalog(testCatalog(tenant, letters)).query()!! }

        fun refused(vararg dependencies: DeclaredCatalogDependency, message: String) = assertThatThrownBy { update(tenant, *dependencies) }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.CATALOG_DEPENDENCY_INVALID)
            .hasMessageContaining(message)

        // A declaration pins a release; a range is not one (contract 2.0).
        refused(good.copy(releaseVersion = "^1.0.0"), message = "must be a release number")
        refused(good, good.copy(releaseVersion = "1.0.0"), message = "declared more than once")
        refused(DeclaredCatalogDependency(own.registry, own.namespace, letters.value, "1.0.0"), message = "cannot depend on itself")
        refused(good.copy(namespace = "elsewhere"), message = "has no catalog")
    }

    @Test
    fun `a dependency renamed to another namespace stays declared, with its pin`() {
        val tenant = setUp("deps-rename", "1.0.0")
        val before = update(tenant, declared(tenant, "1.0.0")).single()

        withMediator {
            TestNamespaces.grant(tenant, "renamed").execute()
            ChangeCatalogNamespace(testCatalog(tenant, shared), "renamed").execute()
        }

        val after = withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }.single()
        assertThat(after).isEqualTo(before.copy(namespace = "renamed"))
    }

    @Test
    fun `moving a resource another catalog uses into a third declares the dependency it creates`() {
        val tenant = setUp("deps-move-declares", "1.0.0")
        withMediator {
            // letters' invoice uses letters' own theme, which then moves to shared.
            CreateTheme(ThemeId(ThemeKey.of("house"), testCatalog(TenantId(tenant), letters)), "House").execute()
            val templateId = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("house"), themeCatalog = testCatalog(templateId.tenantKey, letters)).execute()
            val variant = VariantId(VariantKey.INITIAL, templateId)
            UpdateVariantContent(variant, plainModel()).execute()
            MarkVariantReady(variant).execute()
        }
        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }).isEmpty()

        val relocations = listOf(testAddress(tenant, CatalogResourceType.THEME, letters.value, "house").movedTo(shared))
        withMediator {
            val plan = PreviewCatalogResourceMove(tenant, relocations).query()
            MoveCatalogResources(tenant, relocations, plan.planFingerprint).execute()
        }

        // On shared's draft (ADR 0042): no release of shared holds the theme yet, so its highest
        // release, 1.0.0, would not resolve it.
        val declared = withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }.single()
        assertThat(declared.catalogKey to declared.releaseVersion).isEqualTo(shared.value to null)
        assertThat(declared.namesDraft).`as`("names shared's draft").isTrue()
    }

    @Test
    fun `renaming a resource another catalog uses makes that catalog name the renamed one's next version`() {
        val tenant = setUp("deps-rename-follows", "1.0.0")
        update(tenant, declared(tenant, "1.0.0"))
        invoiceOnSharedTheme(tenant)

        val relocations = listOf(testAddress(tenant, CatalogResourceType.THEME, shared.value, "brand").movedTo(shared, "house"))
        withMediator {
            val plan = PreviewCatalogResourceMove(tenant, relocations).query()
            MoveCatalogResources(tenant, relocations, plan.planFingerprint).execute()
        }

        // Shared 1.0.0 holds `brand`, not `house`: a pin of it would leave the invoice naming a theme
        // that does not exist there, so neither marking it ready nor releasing letters could succeed.
        val declared = withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }.single()
        assertThat(declared.catalogKey to declared.releaseVersion).isEqualTo(shared.value to null)
        assertThat(declared.namesDraft).`as`("names shared's draft").isTrue()
    }

    // ── Releasing ──

    @Test
    fun `a release records the release each dependency pins, and the manifest carries it`() {
        val tenant = setUp("deps-release", "1.0.0")
        update(tenant, declared(tenant, "1.0.0"))
        invoiceOnSharedTheme(tenant)
        releaseShared(tenant, "1.1.0", "#111111")

        withMediator { MarkCatalogReady(testCatalog(tenant, letters)).execute() }
        withMediator { ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0").execute() }

        val pins = withMediator { ListReleaseDependencies(testCatalog(tenant, letters), "1.0.0").query() }
        assertThat(pins.map { it.catalogKey to it.releaseVersion }).containsExactly(shared.value to "1.0.0")
        val sharedFingerprint = pins.single().releaseFingerprint
        assertThat(sharedFingerprint).isEqualTo(releaseFingerprint(tenant, shared, "1.0.0"))
        val archive = withMediator { ExportCatalogZip(testCatalog(tenant, letters), version = "1.0.0").execute() }.zipBytes
        assertThat(String(entry(archive, "catalog.json"))).contains("\"releaseVersion\":\"1.0.0\"", "\"releaseFingerprint\":\"$sharedFingerprint\"")
    }

    @Test
    fun `a catalog used before it has a release is declared on its draft, which its release then pins`() {
        val tenant = setUp("deps-late")
        invoiceOnSharedTheme(tenant)
        // shared has no release yet, so the declaration this adds names its next version.
        val declared = withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }.single()
        assertThat(declared.releaseVersion).isNull()
        assertThat(declared.draftName).isEqualTo("Next release")

        withMediator { MarkCatalogReady(testCatalog(tenant, letters)).execute() }
        assertThatThrownBy { withMediator { ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0").execute() } }
            .isInstanceOf(CatalogDependenciesUnreleasedException::class.java)
            .hasMessageContaining("shared (Next release)")

        releaseShared(tenant, "1.0.0", "#111111")
        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }.single().releaseVersion)
            .`as`("releasing the draft it named pins its release").isEqualTo("1.0.0")
        withMediator { ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0").execute() }
        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant, letters), "1.0.0").query() }.single().releaseVersion).isEqualTo("1.0.0")
    }

    @Test
    fun `a dependency declared by name, of a catalog with nothing released, is published along and pinned`() {
        val tenant = setUp("deps-by-name")
        invoiceOnSharedTheme(tenant)
        // What the 2.0 upgrade leaves for what a 1.x catalog used: a declaration by name, pinning no
        // release and naming no draft. No command makes one (a declaration names one or the other).
        withMediator {
            jdbi.useHandle<Exception> {
                it.createUpdate("UPDATE catalog_version_dependencies SET release_version = NULL, release_fingerprint = NULL, target_version_id = NULL WHERE tenant_key = :t")
                    .bind("t", tenant)
                    .execute()
            }
        }
        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters)).query() }.single().let { it.pinned || it.namesDraft }).isFalse()

        // Publishing letters publishes shared's next version first, as the publish dialog lists it.
        val along = withMediator { GetCatalogBorrowing(testCatalog(tenant, letters)).query() }.publishedAlong
        assertThat(along.map { it.catalogKey to it.draftName }).containsExactly(shared.value to "Next release")

        withMediator { MarkCatalogReady(testCatalog(tenant, shared)).execute() }
        withMediator { MarkCatalogReady(testCatalog(tenant, letters)).execute() }
        val result = withMediator { ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0", releaseDependencies = true).execute() }

        assertThat(result.releasedDependencies.map { it.catalogKey to it.releaseVersion }).containsExactly(shared.value to "1.0.0")
        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant, letters), "1.0.0").query() }.single().releaseVersion).isEqualTo("1.0.0")
    }

    @Test
    fun `a release using a catalog it does not declare is refused`() {
        val tenant = setUp("deps-undeclared", "1.0.0")
        invoiceOnSharedTheme(tenant)
        // Using Shared declared it; an author then removed the declaration.
        update(tenant)

        withMediator { MarkCatalogReady(testCatalog(tenant, letters)).execute() }
        assertThatThrownBy { withMediator { ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0").execute() } }
            .hasMessageContaining("which catalogDependencies does not declare")
    }

    // ── Installing ──

    @Test
    fun `an installed catalog keeps the pins its archive was cut with and renders with them`() {
        val origin = setUp("deps-origin", "1.0.0")
        update(origin, declared(origin, "1.0.0"))
        invoiceOnSharedTheme(origin)
        withMediator { MarkCatalogReady(testCatalog(origin, letters)).execute() }
        withMediator { ReleaseCatalogDraft(testCatalog(origin, letters), "1.0.0").execute() }
        releaseShared(origin, "1.1.0", "#ff0000")
        val sharedArchives = listOf("1.0.0", "1.1.0").map { version -> withMediator { ExportCatalogZip(testCatalog(origin, shared), version).execute() }.zipBytes }
        val lettersArchive = withMediator { ExportCatalogZip(testCatalog(origin, letters), "1.0.0").execute() }.zipBytes

        val target = createTenant("deps-target").id
        withMediator {
            sharedArchives.forEach { ImportCatalogZip(target, it, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
            ImportCatalogZip(target, lettersArchive, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
        }

        val pin = withMediator { ListReleaseDependencies(testCatalog(target, letters), "1.0.0").query() }.single()
        assertThat(pin.releaseVersion).isEqualTo("1.0.0")
        assertThat(pin.releaseFingerprint).isEqualTo(releaseFingerprint(origin, shared, "1.0.0"))
        val tenant = withMediator { GetTenant(target).query()!! }
        val inputs = source.resolve(tenant, ReleaseRef(testCatalog(tenant.id, letters), "1.0.0"), "invoice", VariantKey.INITIAL.value)
        assertThat(inputs.resolvedTheme.documentStyles)
            .`as`("shared@1.0.0 as pinned, though the target installed shared@1.1.0 since")
            .containsEntry("color", "#000000")
    }

    private fun releaseFingerprint(tenant: TenantKey, catalog: CatalogKey, version: String): String = withMediator {
        ListCatalogReleaseSummaries(testCatalog(tenant, catalog)).query().single { it.version == version }.fingerprint
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
