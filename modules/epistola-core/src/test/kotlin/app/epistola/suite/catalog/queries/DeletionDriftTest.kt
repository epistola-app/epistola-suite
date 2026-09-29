// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.DeleteCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Deleting a resource counts as an unreleased change.
 *
 * The drift signal is a timestamp comparison — `last_activity > GREATEST(released_at, imported_at)`
 * over the resources' own `updated_at` / `published_at` — and a deletion moves none of those. The row
 * simply goes, so the MAX can even move backwards, and a catalog whose only change since its last
 * release was a deletion reported **no** unreleased changes. The release dialog, which diffs digests
 * against `release_entries`, reported the resource being dropped. Two answers to one question, and
 * the quiet one told the reader there was nothing to review right before a release removed something.
 *
 * The failure mode is silence, so each case asserts the catalog was quiet *before* the deletion as
 * well — otherwise a trigger that fired on everything would pass just as happily.
 */
class DeletionDriftTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `deleting a theme is an unreleased change`() = assertDeletionDrifts(
        slug = "drift-theme",
        seed = { catalog -> CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute() },
        delete = { catalog -> DeleteTheme(ThemeId(ThemeKey.of("brand"), catalog)).execute() },
    )

    @Test
    fun `deleting a stencil is an unreleased change`() = assertDeletionDrifts(
        slug = "drift-stencil",
        seed = { catalog -> CreateStencil(id = StencilId(StencilKey.of("address"), catalog), name = "Address").execute() },
        delete = { catalog -> DeleteStencil(StencilId(StencilKey.of("address"), catalog)).execute() },
    )

    @Test
    fun `deleting a code list is an unreleased change`() = assertDeletionDrifts(
        slug = "drift-codelist",
        seed = { catalog ->
            CreateCodeList(
                id = CodeListId(CodeListKey.of("locales"), catalog),
                displayName = "Locales",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English")),
            ).execute()
        },
        delete = { catalog -> DeleteCodeList(CodeListId(CodeListKey.of("locales"), catalog)).execute() },
    )

    @Test
    fun `deleting a template is an unreleased change`() = assertDeletionDrifts(
        slug = "drift-template",
        seed = { catalog ->
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice")
                .execute().withRequiredDataExample()
        },
        delete = { catalog -> DeleteDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog)).execute() },
    )

    /**
     * Every catalog-scoped resource table carries the trigger.
     *
     * The cases above cover four types by behaviour; this covers the rest without needing a create
     * command for each, and keeps covering a type added later. Raw `information_schema` because the
     * subject is the schema itself, which no read model reports.
     */
    @Test
    fun `every catalog-scoped resource table bumps the catalog when a row is deleted`() {
        val expected = setOf(
            "document_templates",
            "template_variants",
            "themes",
            "stencils",
            "code_lists",
            "fonts",
            "assets",
            "variant_attribute_definitions",
        )

        val covered = jdbi.withHandle<Set<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT event_object_table
                FROM information_schema.triggers
                WHERE trigger_schema = 'public' AND action_timing = 'AFTER' AND event_manipulation = 'DELETE'
                  AND action_statement LIKE '%touch_catalog_content%'
                """,
            )
                .mapTo(String::class.java)
                .set()
        }

        assertThat(covered)
            .`as`("a catalog-scoped table without the trigger goes back to hiding its deletions")
            .containsAll(expected)
    }

    /**
     * Seeds a resource, releases, asserts the catalog is quiet, deletes the resource, asserts it is
     * not. The quiet assertion in the middle is what makes the second one mean something.
     */
    private fun assertDeletionDrifts(slug: String, seed: (CatalogId) -> Unit, delete: (CatalogId) -> Unit) {
        val tenant = createTenant("Deletion drift $slug")
        val key = CatalogKey.of(slug)
        val catalog = CatalogId(key, TenantId(tenant.id))

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = slug).execute()
            seed(catalog)
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()
        }

        assertThat(pendingChanges(tenant.id, key))
            .`as`("freshly released, so nothing is waiting")
            .isFalse()

        withMediator { delete(catalog) }

        assertThat(pendingChanges(tenant.id, key))
            .`as`("the deletion is the only change since the release, and it counts")
            .isTrue()
    }

    private fun pendingChanges(tenantKey: TenantKey, catalogKey: CatalogKey): Boolean = withMediator {
        ListCatalogsForManagement(tenantKey).query().single { it.catalog.id == catalogKey }.pendingChanges
    }
}
