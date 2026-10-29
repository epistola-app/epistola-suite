// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.releases.CatalogPreset
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.catalog.releases.SetCatalogPresets
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.parallelReleaseLines
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A conflict resolved with merged content (contract ADR 0005): its sides read in the portable catalog
 * format ([GetDraftMergeSides]), combined, and merged back ([MergeReleaseIntoDraft.mergedContent]).
 *
 * `letters` 1.0.0 has the template `invoice` with the variants `initial` and `english`. Parallel
 * work made from 1.0.0 is released as 1.1.0 and merged into the next release.
 */
class MergedContentTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))

    private fun variant(tenant: TenantKey, key: String) = VariantId(VariantKey.of(key), invoice(tenant))

    private fun retitle(tenant: TenantKey, key: String, title: String, version: CatalogReleaseRef? = null) = UpdateVariant(variant(tenant, key), title, emptyMap(), catalogVersion = version).execute()

    /** The catalog; [fix] changes the parallel work before it is released as 1.1.0, [next] the next release. */
    private fun lines(name: String, fix: (TenantKey, CatalogReleaseRef) -> Unit, next: (TenantKey) -> Unit = {}): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), letters)), name = "Brand").execute()
            CreateDocumentTemplate(id = invoice(tenant), name = "Invoice").execute()
            CreateVariant(variant(tenant, "english"), title = "English", description = null).execute()
            MarkCatalogReady(testCatalog(tenant, letters)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.0.0").execute()

            val fixRef = CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(CreateCatalogDraft(testCatalog(tenant, letters), "Fix", "1.0.0").execute().versionId))
            fix(tenant, fixRef)
            MarkCatalogReady(testCatalog(tenant, letters), catalogVersion = fixRef).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.1.0", catalogVersion = fixRef).execute()

            next(tenant)
        }
        return tenant
    }

    /** The fix retitles `english`, the next release retitles `initial`: the template is a conflict. */
    private fun bothRetitled(name: String) = lines(
        name,
        fix = { tenant, fixRef -> retitle(tenant, "english", "English (fixed)", fixRef) },
        next = { tenant -> retitle(tenant, "initial", "Standard") },
    )

    @Suppress("UNCHECKED_CAST")
    private fun variantsOf(side: Map<String, Any?>?): List<Map<String, Any?>> = side!!["variants"] as List<Map<String, Any?>>

    private fun titles(side: Map<String, Any?>?): Map<Any?, Any?> = variantsOf(side).associate { it["key"] to it["name"] }

    /** The draft's side with each variant's name [from] the side that changed it. */
    private fun combined(sides: DraftMergeSides): Map<String, Any?> {
        val theirs = variantsOf(sides.release).associateBy { it["key"] }
        return sides.draft!! + ("variants" to variantsOf(sides.draft).map { if (it["key"] == "english") it + ("name" to theirs.getValue("english")["name"]) else it })
    }

    @Test
    fun `the sides of a conflict are its content in the merge base, the draft and the release`() {
        val tenant = bothRetitled("Merge Sides Conflict")
        withMediator {
            val preview = PreviewDraftMerge(testCatalog(tenant, letters), "1.1.0").query()
            assertThat(preview.conflicts.map { it.contentKey }).containsExactly("template/invoice")

            val sides = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "template/invoice").query()

            assertThat(sides.entry).isEqualTo("template/invoice")
            assertThat(sides.mergeBase).isEqualTo("1.0.0")
            assertThat(sides.token).`as`("the preview's").isEqualTo(preview.token)
            listOf(sides.base, sides.draft, sides.release).forEach { assertThat(it).containsEntry("type", "template").containsEntry("key", "invoice").containsEntry("name", "Invoice") }
            assertThat(titles(sides.base)).containsExactlyInAnyOrderEntriesOf(mapOf("initial" to "Default", "english" to "English"))
            assertThat(titles(sides.draft)).containsExactlyInAnyOrderEntriesOf(mapOf("initial" to "Standard", "english" to "English"))
            assertThat(titles(sides.release)).containsExactlyInAnyOrderEntriesOf(mapOf("initial" to "Default", "english" to "English (fixed)"))
        }
    }

    @Test
    fun `a side that does not have the entry is null, and an entry the merge does not list is not found`() {
        val tenant = lines(
            "Merge Sides Missing",
            fix = { tenant, fixRef -> CreateTheme(id = ThemeId(ThemeKey.of("extra"), testCatalog(TenantId(tenant), letters)), name = "Extra", catalogVersion = fixRef).execute() },
        )
        withMediator {
            val sides = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "theme/extra").query()
            assertThat(sides.base).isNull()
            assertThat(sides.draft).isNull()
            assertThat(sides.release).containsEntry("type", "theme").containsEntry("key", "extra").containsEntry("name", "Extra")

            listOf("template/invoice", "theme/nothing").forEach { entry ->
                assertThatThrownBy { GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", entry).query() }
                    .`as`(entry).isInstanceOf(MergeEntryNotFoundException::class.java)
            }
        }
    }

    @Test
    fun `the sides of a setting and of a dependency are their values as a manifest carries them`() {
        val tenant = createTenant("Merge Sides Settings").id
        withMediator {
            CreateCatalog(tenantKey = tenant, id = shared, name = "Shared").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("base"), testCatalog(TenantId(tenant), shared)), name = "Base").execute()
            MarkCatalogReady(testCatalog(tenant, shared)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, shared), version = "1.0.0").execute()
        }
        val sharedName = withMediator { GetCatalog(testCatalog(tenant, shared)).query()!! }
        val fingerprint = withMediator { ListCatalogReleases(testCatalog(tenant, shared)).query().single { it.version == "1.0.0" }.fingerprint }
        // The parallel work pins shared 1.0.0 and declares a preset; the next release names shared's draft and declares another.
        withMediator {
            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("own"), testCatalog(TenantId(tenant), letters)), name = "Own").execute()
            MarkCatalogReady(testCatalog(tenant, letters)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.0.0").execute()
            val fixRef = CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(CreateCatalogDraft(testCatalog(tenant, letters), "Fix", "1.0.0").execute().versionId))
            UpdateCatalogDependencies(testCatalog(tenant, letters), listOf(DeclaredCatalogDependency(sharedName.registry, sharedName.namespace, "shared", releaseVersion = "1.0.0")), catalogVersion = fixRef).execute()
            SetCatalogPresets(testCatalog(tenant, letters), listOf(CatalogPreset("callout", "Callout")), catalogVersion = fixRef).execute()
            MarkCatalogReady(testCatalog(tenant, letters), catalogVersion = fixRef).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.1.0", catalogVersion = fixRef).execute()

            val sharedDraft = ListCatalogReleases(testCatalog(tenant, shared)).query().single { it.isNext }.versionId
            UpdateCatalogDependencies(testCatalog(tenant, letters), listOf(DeclaredCatalogDependency(sharedName.registry, sharedName.namespace, "shared", versionId = sharedDraft))).execute()
            SetCatalogPresets(testCatalog(tenant, letters), listOf(CatalogPreset("note", "Note", "A side note"))).execute()

            val presets = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "catalog/presets").query()
            assertThat(presets.base).isEqualTo(mapOf("presets" to emptyList<Any>()))
            assertThat(presets.draft).isEqualTo(mapOf("presets" to listOf(mapOf("key" to "note", "name" to "Note", "description" to "A side note"))))
            assertThat(presets.release).isEqualTo(mapOf("presets" to listOf(mapOf("key" to "callout", "name" to "Callout"))))

            val dependency = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "catalogDependency/shared").query()
            assertThat(dependency.base).isNull()
            assertThat(dependency.release).isEqualTo(
                mapOf("alias" to "shared", "registry" to sharedName.registry, "namespace" to sharedName.namespace, "catalogKey" to "shared", "releaseVersion" to "1.0.0", "releaseFingerprint" to fingerprint),
            )
            assertThat(dependency.draft).`as`("a draft named by its versionId, pinning no release").isEqualTo(
                mapOf("alias" to "shared", "registry" to sharedName.registry, "namespace" to sharedName.namespace, "catalogKey" to "shared", "releaseVersion" to null, "releaseFingerprint" to null, "releaseId" to sharedDraft),
            )

            val defaultTheme = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "catalog/defaultTheme")
            assertThatThrownBy { defaultTheme.query() }.`as`("neither side changed it").isInstanceOf(MergeEntryNotFoundException::class.java)
        }
    }

    @Test
    fun `the sides cannot be read without the merge base`() {
        val producer = createTenant("Merge Sides Producer").id
        val tenant = createTenant("Merge Sides No Base").id
        val catalog = CatalogKey.of("sides-lines")
        withMediator {
            val lines = mediator.parallelReleaseLines(producer, catalog)
            ImportCatalogZip(tenantKey = tenant, zipBytes = lines.v110, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            ImportCatalogZip(tenantKey = tenant, zipBytes = lines.v101, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            assertThatThrownBy { GetDraftMergeSides(testCatalog(tenant, catalog), "1.0.1", "theme/brand").query() }
                .isInstanceOfSatisfying(MergeBaseMissingException::class.java) { assertThat(it.version).isEqualTo("1.0.0") }
        }
    }

    @Test
    fun `a conflict resolved to merged content keeps both changes, and is changed and not ready`() {
        val tenant = bothRetitled("Merge Merged Template")
        withMediator {
            val sides = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "template/invoice").query()

            val merged = MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", expectedToken = sides.token, mergedContent = mapOf("template/invoice" to combined(sides))).execute()

            assertThat(merged.merged).containsExactly("template/invoice")
            assertThat(merged.taken).isEmpty()
            assertThat(merged.kept).isEmpty()
            assertThat(PreviewDraftMerge(testCatalog(tenant, letters), "1.1.0").query().alreadyMerged).isTrue()
            val template = GetCatalogResourceChanges(testCatalog(tenant, letters)).query().resources.single { it.type == "template" && it.slug == "invoice" }
            assertThat(template.state).isEqualTo(CatalogResourceState.MODIFIED)
            assertThat(template.ready).`as`("merged content is reviewed before it is released").isFalse()
        }
        withMediator {
            val titles = app.epistola.suite.templates.queries.variants.ListVariants(invoice(tenant)).query().associate { it.id.value to it.title }
            assertThat(titles).containsExactlyInAnyOrderEntriesOf(mapOf("initial" to "Standard", "english" to "English (fixed)"))
        }
    }

    @Test
    fun `merged content awaits review until every part of it is marked ready, and an edit alone does not end it`() {
        val tenant = bothRetitled("Merge Merged Review")
        fun awaiting() = GetCatalogResourceChanges(testCatalog(tenant, letters)).query().mergedForReview.map { "${it.type}/${it.slug}" }
        withMediator {
            assertThat(awaiting()).`as`("nothing merged yet").isEmpty()
            val sides = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "template/invoice").query()
            MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", expectedToken = sides.token, mergedContent = mapOf("template/invoice" to combined(sides))).execute()
        }
        withMediator { assertThat(awaiting()).containsExactly("template/invoice") }

        withMediator { retitle(tenant, "initial", "Standard, edited") }
        withMediator { assertThat(awaiting()).`as`("an edit leaves the rest of the merged content unreviewed").containsExactly("template/invoice") }

        withMediator { app.epistola.suite.templates.commands.variants.MarkVariantReady(variant(tenant, "initial")).execute() }
        withMediator { assertThat(awaiting()).`as`("one of its two variants marked").containsExactly("template/invoice") }

        withMediator { app.epistola.suite.templates.commands.variants.MarkVariantReady(variant(tenant, "english")).execute() }
        withMediator { assertThat(awaiting()).isEmpty() }

        withMediator { retitle(tenant, "english", "English, edited after review") }
        withMediator {
            val template = GetCatalogResourceChanges(testCatalog(tenant, letters)).query().resources.single { it.type == "template" && it.slug == "invoice" }
            assertThat(template.ready).isFalse()
            assertThat(template.mergedForReview).`as`("reviewed since the merge; now an ordinary change").isFalse()
        }
    }

    @Test
    fun `a conflict resolved to a side is not merged content to review`() {
        val tenant = bothRetitled("Merge Side Not Merged")
        withMediator {
            MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", resolutions = mapOf("template/invoice" to MergeResolution.Ours)).execute()
            assertThat(GetCatalogResourceChanges(testCatalog(tenant, letters)).query().mergedForReview).isEmpty()
        }
    }

    @Test
    fun `merged content using a stencil the result does not have refuses the merge`() {
        val tenant = bothRetitled("Merge Merged Missing Stencil")
        withMediator {
            val sides = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "template/invoice").query()
            val content = combined(sides).let { it + ("templateModel" to withStencil(it["templateModel"], "ghost")) }

            assertThatThrownBy { MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", mergedContent = mapOf("template/invoice" to content)).execute() }
                .isInstanceOf(MergeRefusedException::class.java)
                .hasMessageContaining("template 'invoice' uses stencil 'ghost', which this catalog does not have")
            assertThat(PreviewDraftMerge(testCatalog(tenant, letters), "1.1.0").query().alreadyMerged).`as`("nothing changed").isFalse()
        }
    }

    @Test
    fun `merged content that is not valid for its entry is refused, the field naming the entry`() {
        val tenant = bothRetitled("Merge Merged Invalid")
        withMediator {
            val sides = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "template/invoice").query()
            val good = combined(sides)
            val cases = mapOf(
                good + ("key" to "Invoice!") to "mergedContent.template/invoice/key",
                good + ("type" to "theme") to "mergedContent.template/invoice/type",
                // A second entry of a variant that is not the default: the key is what clashes.
                good + ("variants" to variantsOf(good) + variantsOf(good).first { it["isDefault"] != true }) to "mergedContent.template/invoice/variants[2]/key",
                // The wire-v8 schema check names the first member that breaks it.
                good + ("name" to 42) + ("variants" to "none") to "mergedContent.template/invoice/name",
            )
            cases.forEach { (content, field) ->
                assertThatThrownBy { MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", mergedContent = mapOf("template/invoice" to content)).execute() }
                    .`as`(field)
                    .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.field).isEqualTo(field) }
            }
            assertThat(PreviewDraftMerge(testCatalog(tenant, letters), "1.1.0").query().alreadyMerged).`as`("nothing changed").isFalse()
        }
    }

    @Test
    fun `merged content for an entry that is not a conflict does not apply`() {
        val tenant = lines(
            "Merge Merged Not Conflict",
            fix = { tenant, fixRef -> retitle(tenant, "english", "English (fixed)", fixRef) },
        )
        withMediator {
            val sides = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "template/invoice").query()
            assertThatThrownBy { MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", mergedContent = mapOf("template/invoice" to sides.release!!)).execute() }
                .isInstanceOf(MergeNotApplicableException::class.java)
                .hasMessageContaining("template/invoice")
        }
    }

    @Test
    fun `a conflict resolved both to a side and to merged content is refused`() {
        assertThatThrownBy {
            MergeReleaseIntoDraft(testCatalog(TenantKey.of("acme"), letters), "1.1.0", resolutions = mapOf("template/invoice" to MergeResolution.Theirs), mergedContent = mapOf("template/invoice" to mapOf("type" to "template")))
        }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.field).isEqualTo("mergedContent.template/invoice") }
    }

    @Test
    fun `merged content made from an outdated preview is refused`() {
        val tenant = bothRetitled("Merge Merged Outdated")
        withMediator {
            val sides = GetDraftMergeSides(testCatalog(tenant, letters), "1.1.0", "template/invoice").query()
            retitle(tenant, "initial", "Standard (again)")
            assertThatThrownBy { MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", expectedToken = sides.token, mergedContent = mapOf("template/invoice" to combined(sides))).execute() }
                .isInstanceOf(MergePreviewOutdatedException::class.java)
        }
    }

    @Test
    fun `a setting resolved to merged content is written as given`() {
        val tenant = lines(
            "Merge Merged Presets",
            fix = { tenant, fixRef -> SetCatalogPresets(testCatalog(tenant, letters), listOf(CatalogPreset("callout", "Callout")), catalogVersion = fixRef).execute() },
            next = { tenant -> SetCatalogPresets(testCatalog(tenant, letters), listOf(CatalogPreset("note", "Note"))).execute() },
        )
        withMediator {
            val merged = mapOf("presets" to listOf(mapOf("key" to "callout", "name" to "Callout"), mapOf("key" to "note", "name" to "Note")))
            assertThatThrownBy {
                MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", mergedContent = mapOf("catalog/presets" to mapOf("presets" to listOf(mapOf("key" to "Bad Key", "name" to "Bad"))))).execute()
            }.isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.field).isEqualTo("mergedContent.catalog/presets/presets[0]") }

            assertThat(MergeReleaseIntoDraft(testCatalog(tenant, letters), "1.1.0", mergedContent = mapOf("catalog/presets" to merged)).execute().merged).containsExactly("catalog/presets")
            assertThat(GetCatalogThemeSettings(testCatalog(tenant, letters)).query().presets).containsExactly(CatalogPreset("callout", "Callout"), CatalogPreset("note", "Note"))
        }
    }

    /** [model] (a template document) with a stencil insertion of [stencilKey] added to its root. */
    @Suppress("UNCHECKED_CAST")
    private fun withStencil(model: Any?, stencilKey: String): Map<String, Any?> {
        val document = model as Map<String, Any?>
        val nodes = document["nodes"] as Map<String, Any?>
        val slots = document["slots"] as Map<String, Any?>
        val root = nodes.getValue(document["root"] as String) as Map<String, Any?>
        val rootSlot = (root["slots"] as List<String>).first()
        val slot = slots.getValue(rootSlot) as Map<String, Any?>
        return document + mapOf(
            "nodes" to nodes + ("ghost" to mapOf("id" to "ghost", "type" to "stencil", "slots" to listOf("ghost-slot"), "props" to mapOf("stencilKey" to stencilKey))),
            "slots" to slots + mapOf(
                rootSlot to slot + ("children" to (slot["children"] as List<String>) + "ghost"),
                "ghost-slot" to mapOf("id" to "ghost-slot", "nodeId" to "ghost", "name" to "children", "children" to emptyList<String>()),
            ),
        )
    }
}
