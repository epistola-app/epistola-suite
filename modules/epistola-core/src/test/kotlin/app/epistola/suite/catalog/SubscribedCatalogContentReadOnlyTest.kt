// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.ArchiveStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

private const val FIXTURE_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

/**
 * #1023: content of a SUBSCRIBED catalog — an installed catalog — is read-only, so every command
 * that writes it must call `requireCatalogEditable`. Otherwise the installed copy drifts from the
 * release it came from, and the next upgrade overwrites the change or conflicts with it. Each test
 * targets real installed content, so a missing guard shows up as an edit that went through.
 */
class SubscribedCatalogContentReadOnlyTest : IntegrationTestBase() {

    private val installed = CatalogKey.of("epistola-demo")

    private fun installFixture(): TenantKey = withMediator {
        val tenantKey = createTenant("Subscribed read-only").id
        RegisterCatalog(tenantKey = tenantKey, sourceUrl = FIXTURE_CATALOG_URL, authType = AuthType.NONE).execute()
        InstallFromCatalog(tenantKey = tenantKey, catalogKey = installed).execute()
        tenantKey
    }

    private fun simpleLetter(tenantKey: TenantKey) = TemplateId(TemplateKey.of("simple-letter"), CatalogId(installed, TenantId(tenantKey)))

    private fun firstVariant(tenantKey: TenantKey) = withMediator { ListVariants(simpleLetter(tenantKey)).query().first() }

    /** The installed version of the fixture's `company-header` stencil, whatever number the release gave it. */
    private fun companyHeaderVersion(tenantKey: TenantKey): StencilVersionId {
        val stencil = StencilId(StencilKey.of("company-header"), CatalogId(installed, TenantId(tenantKey)))
        val version = withMediator { ListStencilVersions(stencil).query().first() }
        return StencilVersionId(version.id, stencil)
    }

    @Test
    fun `UpdateVariant is refused and leaves the installed variant as it was`() {
        val tenantKey = installFixture()
        val variant = firstVariant(tenantKey)

        assertThatThrownBy {
            withMediator { UpdateVariant(VariantId(variant.id, simpleLetter(tenantKey)), title = "Retitled", attributes = variant.attributes).execute() }
        }.isInstanceOf(CatalogReadOnlyException::class.java)

        assertThat(firstVariant(tenantKey).title).isEqualTo(variant.title)
    }

    @Test
    fun `SetDefaultVariant is refused`() {
        val tenantKey = installFixture()
        val variant = firstVariant(tenantKey)

        assertThatThrownBy {
            withMediator { SetDefaultVariant(VariantId(variant.id, simpleLetter(tenantKey))).execute() }
        }.isInstanceOf(CatalogReadOnlyException::class.java)
    }

    @Test
    fun `ArchiveStencilVersion is refused`() {
        val tenantKey = installFixture()

        assertThatThrownBy {
            withMediator { ArchiveStencilVersion(companyHeaderVersion(tenantKey)).execute() }
        }.isInstanceOf(CatalogReadOnlyException::class.java)
    }

    @Test
    fun `PublishStencilVersion is refused`() {
        val tenantKey = installFixture()

        assertThatThrownBy {
            withMediator { PublishStencilVersion(companyHeaderVersion(tenantKey)).execute() }
        }.isInstanceOf(CatalogReadOnlyException::class.java)
    }

    @Test
    fun `ImportFont is refused outside a catalog import`() {
        val tenantKey = installFixture()

        assertThatThrownBy {
            withMediator {
                ImportFont(tenantId = TenantId(tenantKey), catalogKey = installed, slug = "rogue-sans", name = "Rogue Sans", kind = "sans").execute()
            }
        }.isInstanceOf(CatalogReadOnlyException::class.java)
    }
}
