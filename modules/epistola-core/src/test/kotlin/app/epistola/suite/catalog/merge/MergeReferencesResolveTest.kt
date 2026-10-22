// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Every reference in a merge's result resolves (ADR 0032): within the catalog, and into another one
 * through the dependency the result declares on it, against the release that declaration pins. A
 * merge that would leave one unresolved is refused, saying what is missing and what to choose.
 *
 * `shared` has releases 1.0.0 (theme `base`), 1.1.0 (`base` and `extra`) and 1.2.0 (and `more`).
 * `letters` 1.0.0 pins one of them; parallel work made from it is released as 1.1.0 and merged into
 * the next release.
 */
class MergeReferencesResolveTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), CatalogId(catalog, TenantId(tenant)))

    private fun declare(tenant: TenantKey, pins: Map<CatalogKey, String>, version: CatalogReleaseRef? = null) {
        val declared = pins.map { (on, release) ->
            val target = GetCatalog(tenant, on).query()!!
            DeclaredCatalogDependency(target.registry, target.namespace, on.value, releaseVersion = release)
        }
        UpdateCatalogDependencies(tenant, letters, declared, catalogVersion = version).execute()
    }

    /** The catalogs; [fix] changes the fix before it is released as 1.0.1, [next] the next release. */
    private fun lines(name: String, basePin: String = "1.0.0", fix: (TenantKey, CatalogReleaseRef) -> Unit, next: (TenantKey) -> Unit = {}): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            CreateCatalog(tenantKey = tenant, id = shared, name = "Shared").execute()
            CreateTheme(id = theme(tenant, shared, "base"), name = "Base").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = shared, version = "1.0.0").execute()
            CreateTheme(id = theme(tenant, shared, "extra"), name = "Extra").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = shared, version = "1.1.0").execute()
            CreateTheme(id = theme(tenant, shared, "more"), name = "More").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = shared, version = "1.2.0").execute()

            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            CreateTheme(id = theme(tenant, letters, "own"), name = "Own").execute()
            declare(tenant, mapOf(shared to basePin))
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, version = "1.0.0").execute()

            val fixRef = CatalogReleaseRef(tenant, letters, DraftRef.Id(CreateCatalogDraft(tenant, letters, "Fix", "1.0.0").execute().versionId))
            fix(tenant, fixRef)
            MarkCatalogReady(tenant, letters, catalogVersion = fixRef).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, version = "1.1.0", catalogVersion = fixRef).execute()

            next(tenant)
        }
        return tenant
    }

    @Test
    fun `a default theme of another catalog the result does not declare refuses the merge, naming what to choose`() {
        val tenant = lines(
            "Merge Theme Needs Dependency",
            // The fix uses shared 1.1.0's theme as its default theme.
            fix = { tenant, fixRef ->
                declare(tenant, mapOf(shared to "1.1.0"), fixRef)
                SetCatalogDefaultTheme(tenant, letters, CatalogDefaultTheme(ThemeKey.of("extra"), shared), catalogVersion = fixRef).execute()
            },
            // The next release stops using shared, and has a default theme of its own.
            next = { tenant ->
                declare(tenant, emptyMap())
                SetCatalogDefaultTheme(tenant, letters, CatalogDefaultTheme(ThemeKey.of("own"), letters)).execute()
            },
        )
        withMediator {
            val preview = PreviewDraftMerge(tenant, letters, "1.1.0").query()
            assertThat(preview.conflicts.map { it.contentKey }).containsExactlyInAnyOrder("catalog/defaultTheme", "catalogDependency/shared")
            assertThat(preview.resources.single { it.contentKey == "catalog/defaultTheme" }.needs)
                .`as`("the default theme goes with the dependency it resolves through")
                .containsExactly("catalogDependency/shared")

            assertThatThrownBy {
                MergeReleaseIntoDraft(
                    tenant,
                    letters,
                    "1.1.0",
                    resolutions = mapOf("catalog/defaultTheme" to MergeResolution.Theirs, "catalogDependency/shared" to MergeResolution.Ours),
                ).execute()
            }
                .isInstanceOf(MergeRefusedException::class.java)
                .hasMessageContaining("the default theme 'extra' of 'shared' needs 'shared' as a dependency: take v1.1.0's dependency on 'shared' too, or keep your default theme")
            assertThat(GetCatalogThemeSettings(tenant, letters).query().defaultTheme?.themeKey).`as`("nothing changed").isEqualTo(ThemeKey.of("own"))

            MergeReleaseIntoDraft(
                tenant,
                letters,
                "1.1.0",
                resolutions = mapOf("catalog/defaultTheme" to MergeResolution.Theirs, "catalogDependency/shared" to MergeResolution.Theirs),
            ).execute()
            assertThat(GetCatalogThemeSettings(tenant, letters).query().defaultTheme).isEqualTo(CatalogDefaultTheme(ThemeKey.of("extra"), shared))
            assertThat(ListCatalogDependencies(tenant, letters).query().map { it.catalogKey to it.releaseVersion }).containsExactly("shared" to "1.1.0")
        }
    }

    @Test
    fun `a reference into another catalog is checked against the release the result pins, not that catalog's next release`() {
        val tenant = lines(
            "Merge Pinned Release Lacks",
            basePin = "1.1.0",
            // The fix moves back to shared 1.0.0: nothing of its own uses what 1.1.0 added.
            fix = { tenant, fixRef -> declare(tenant, mapOf(shared to "1.0.0"), fixRef) },
            // The next release moves on to shared 1.2.0 and uses its theme `extra`.
            next = { tenant ->
                declare(tenant, mapOf(shared to "1.2.0"))
                val template = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(letters, TenantId(tenant)))
                CreateDocumentTemplate(id = template, name = "Invoice").execute()
                UpdateDocumentTemplate(id = template, themeId = ThemeKey.of("extra"), themeCatalogKey = shared).execute()
            },
        )
        withMediator {
            // Both moved the pin: choosing the fix's leaves the template's theme where 1.0.0 lacks it.
            assertThatThrownBy { MergeReleaseIntoDraft(tenant, letters, "1.1.0", resolutions = mapOf("catalogDependency/shared" to MergeResolution.Theirs)).execute() }
                .isInstanceOf(MergeRefusedException::class.java)
                .hasMessageContaining("uses theme 'extra' of 'shared', which v1.0.0 of 'shared', the release this catalog uses, does not have")
                .hasMessageContaining("keep your dependency on 'shared'")
            assertThat(ListCatalogDependencies(tenant, letters).query().map { it.catalogKey to it.releaseVersion }).`as`("nothing changed").containsExactly("shared" to "1.2.0")
            assertThat(PreviewDraftMerge(tenant, letters, "1.1.0").query().resources.single { it.contentKey.startsWith("template/") }.needs)
                .containsExactly("catalogDependency/shared")
        }
    }

    @Test
    fun `a resource taken from the release that uses what the draft removed refuses the merge`() {
        val tenant = lines(
            "Merge Own Reference",
            // The fix adds a template on the catalog's own theme.
            fix = { tenant, fixRef ->
                val template = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(letters, TenantId(tenant)))
                CreateDocumentTemplate(id = template, name = "Letter", catalogVersion = fixRef).execute()
                UpdateDocumentTemplate(id = template, themeId = ThemeKey.of("own"), themeCatalogKey = letters, catalogVersion = fixRef).execute()
            },
            // The next release removes that theme.
            next = { tenant -> app.epistola.suite.themes.commands.DeleteTheme(theme(tenant, letters, "own")).execute() },
        )
        withMediator {
            val preview = PreviewDraftMerge(tenant, letters, "1.1.0").query()
            val template = preview.taken.single { it.type == "template" }
            assertThat(template.needs).containsExactly("theme/own")

            assertThatThrownBy { MergeReleaseIntoDraft(tenant, letters, "1.1.0").execute() }
                .isInstanceOf(MergeRefusedException::class.java)
                .hasMessageContaining("theme 'own'")
        }
    }

    @Test
    fun `a default theme of a catalog the version pins is looked for in the pinned release`() {
        val tenant = createTenant("Default Theme Pinned").id
        withMediator {
            CreateCatalog(tenantKey = tenant, id = shared, name = "Shared").execute()
            CreateTheme(id = theme(tenant, shared, "base"), name = "Base").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = shared, version = "1.0.0").execute()
            // In shared's next release only, not in the release letters pins.
            CreateTheme(id = theme(tenant, shared, "fresh"), name = "Fresh").execute()
            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            declare(tenant, mapOf(shared to "1.0.0"))

            assertThatThrownBy { SetCatalogDefaultTheme(tenant, letters, CatalogDefaultTheme(ThemeKey.of("fresh"), shared)).execute() }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("not in v1.0.0 of 'shared'")
            SetCatalogDefaultTheme(tenant, letters, CatalogDefaultTheme(ThemeKey.of("base"), shared)).execute()
            assertThat(GetCatalogThemeSettings(tenant, letters).query().defaultTheme).isEqualTo(CatalogDefaultTheme(ThemeKey.of("base"), shared))
        }
    }
}
