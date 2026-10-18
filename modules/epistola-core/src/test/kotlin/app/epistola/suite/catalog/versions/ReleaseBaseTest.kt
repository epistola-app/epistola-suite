// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A release records its base, the release its draft started from (contract ADR 0002), and what a
 * release changed is measured against that base rather than against the release below it.
 */
class ReleaseBaseTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: TenantKey) = CatalogId(letters, TenantId(tenant))

    private fun version(tenant: TenantKey, version: String) = withMediator { GetCatalogVersion(tenant, letters, version).query()!! }

    @Test
    fun `a release records the base of the draft it was, and a catalog's first release has none`() {
        val tenant = withMediator {
            val tenant = createTenant("release-base-recorded").id
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant)), "Brand").execute()
            mediator.releaseNext(catalogId(tenant))
            mediator.releaseNext(catalogId(tenant))
            tenant
        }

        assertThat(version(tenant, "1.0.0").base).isNull()
        assertThat(version(tenant, "1.0.1").base).isEqualTo("1.0.0")
    }

    @Test
    fun `long-term work is behind every release since its base, each with what it changed`() {
        val invoice = { tenant: TenantKey -> TemplateId(TemplateKey.of("invoice"), catalogId(tenant)) }
        val tenant = withMediator {
            val tenant = createTenant("release-base-behind").id
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant)), "Brand").execute()
            CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
            mediator.releaseNext(catalogId(tenant))
            CreateCatalogDraft(tenant, letters, "Redesign").execute()
            // The next version changes the invoice and is released, then the theme and is released.
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed").execute()
            mediator.releaseNext(catalogId(tenant))
            UpdateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant)), name = "Brand, new").execute()
            mediator.releaseNext(catalogId(tenant))
            tenant
        }

        assertThat(version(tenant, "1.0.2").base).isEqualTo("1.0.1")
        val work = withMediator { ListCatalogVersions(tenant, letters).query() }.single { it.name == "Redesign" }
        val behind = work.behind.associate { it.version to it.changes }
        assertThat(behind.keys).containsExactly("1.0.1", "1.0.2")
        assertThat(behind["1.0.1"]).containsExactly(ReleasedChange("template", "invoice", "modified"))
        assertThat(behind["1.0.2"]).containsExactly(ReleasedChange("theme", "brand", "modified"))
    }
}
