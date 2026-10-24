// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.ReleasedVersionReadOnlyException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A version reference names a draft or a release (ADR 0033): a release by its number, read-only. The
 * write guard refuses any command that targets a release, before its handler runs; the catalog's
 * next version is untouched.
 */
class ReleasedVersionReadOnlyTest : IntegrationTestBase() {

    @Test
    fun `a release number parses as a released version, a draft id and next as drafts`() {
        assertThat(DraftRef.parse("1.2.0")).isEqualTo(DraftRef.Released("1.2.0"))
        assertThat(DraftRef.parse("k7m2x9qa")).isEqualTo(DraftRef.Id("k7m2x9qa"))
        assertThat(DraftRef.parse("next")).isEqualTo(DraftRef.Next)
        assertThat(DraftRef.parseDraft("1.2.0")).`as`("a release is not a draft").isNull()
    }

    @Test
    fun `a command that targets a release is refused, and nothing is written`() {
        val tenant = createTenant("Released read-only").id
        val letters = CatalogKey.of("letters")
        val catalog = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            testCatalog(tenant, letters).also {
                CreateTheme(ThemeId(ThemeKey.of("base"), it), "Base").execute()
                MarkCatalogReady(it).execute()
                ReleaseCatalogDraft(it, "1.0.0").execute()
            }
        }

        assertThatThrownBy {
            withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand", catalogVersion = CatalogReleaseRef(catalog, DraftRef.Released("1.0.0"))).execute() }
        }.isInstanceOf(ReleasedVersionReadOnlyException::class.java).hasMessageContaining("Release 1.0.0").hasMessageContaining("read-only")

        assertThat(withMediator { ListThemes(catalog.tenantId, catalog = catalog).query() }.map { it.id.value }).doesNotContain("brand")
    }
}
