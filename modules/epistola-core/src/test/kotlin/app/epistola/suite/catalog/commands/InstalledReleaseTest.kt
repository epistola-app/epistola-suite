// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.revisions.ReleaseResourceStore
import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.generation.release.ReleaseRef
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A subscribed catalog is its releases: installing one records the publisher's release, so that
 * generation, which renders releases only, can render it.
 */
class InstalledReleaseTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var entries: ReleaseResourceStore

    @Autowired
    private lateinit var renderSource: ReleaseRenderSource

    @Test
    fun `installing the system catalog records its release, with the fonts seeded beside it`() {
        val tenant = createTenant("Installed system release").id

        val catalog = withMediator { GetCatalog(testCatalog(tenant, SYSTEM_CATALOG_KEY)).query()!! }
        val version = catalog.installedReleaseVersion!!
        val fingerprint = releaseFingerprint(tenant, SYSTEM_CATALOG_KEY, version)
        val recorded = jdbi.withHandle<List<String>, Exception> { entries.entriesOf(it, testCatalog(tenant, SYSTEM_CATALOG_KEY), version).map { e -> e.contentKey } }

        assertThat(fingerprint).`as`("the publisher's fingerprint, not a recomputation").isEqualTo(catalog.installedFingerprint)
        assertThat(recorded).contains("theme/default")
        assertThat(recorded.filter { it.startsWith("font/") })
            .`as`("fonts are seeded after the manifest installs; the release is recorded after them")
            .isNotEmpty()
    }

    @Test
    fun `installing again records nothing new`() {
        val tenant = createTenant("Installed system release again").id

        withMediator { InstallSystemCatalog(tenant).execute() }

        assertThat(releaseCount(tenant, SYSTEM_CATALOG_KEY)).isEqualTo(1)
    }

    @Test
    fun `an authored catalog using the system theme renders it from the installed system release`() {
        val tenant = createTenant("Renders the system release").id
        val templateId = TemplateId(TemplateKey.of("letter"), CatalogId.default(TenantId(tenant)))
        val variant = VariantId(VariantKey.INITIAL, templateId)
        withMediator {
            declareDependency(tenant, CatalogKey.DEFAULT, on = SYSTEM_CATALOG_KEY)
            CreateDocumentTemplate(templateId, "Letter").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("default"), themeCatalog = testCatalog(templateId.tenantKey, SYSTEM_CATALOG_KEY)).execute()
            UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter")).execute()
            mediator.publishAndRelease(variant)
        }

        val tenantRow = withMediator { GetTenant(tenant).query()!! }
        val inputs = renderSource.resolve(tenantRow, ReleaseRef(testCatalog(tenantRow.id, CatalogKey.DEFAULT), "1.0.0"), "letter", VariantKey.INITIAL.value)
        assertThat(inputs.resolvedTheme.documentStyles)
            .`as`("the system theme, read from the release the system catalog installed")
            .isNotEmpty()
    }

    // Read-only: the installed release's fingerprint as stored.
    private fun releaseFingerprint(tenant: TenantKey, catalog: CatalogKey, version: String): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT fingerprint FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
            .bind("t", tenant)
            .bindCatalog("c", testCatalog(tenant, catalog))
            .bind("v", version)
            .map { rs, _ -> rs.getString("fingerprint") }
            .one()
    }

    private fun releaseCount(tenant: TenantKey, catalog: CatalogKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT COUNT(*) FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenant)
            .bindCatalog("c", testCatalog(tenant, catalog))
            .mapTo(Int::class.java)
            .one()
    }
}
