// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.revisions.ReleaseDependencyStore
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
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
    private lateinit var entries: ReleaseEntryStore

    @Autowired
    private lateinit var dependencies: ReleaseDependencyStore

    @Test
    fun `installing the system catalog records its release, with the fonts seeded beside it`() {
        val tenant = createTenant("Installed system release").id

        val catalog = withMediator { GetCatalog(tenant, SYSTEM_CATALOG_KEY).query()!! }
        val version = catalog.installedReleaseVersion!!
        val (fingerprint, retained) = releaseRow(tenant, SYSTEM_CATALOG_KEY, version)
        val recorded = jdbi.withHandle<List<String>, Exception> { entries.entriesOf(it, tenant, SYSTEM_CATALOG_KEY, version).map { e -> e.contentKey } }

        assertThat(retained).isTrue()
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
    fun `an authored catalog using the system theme pins the installed system release`() {
        val tenant = createTenant("Pins the system release").id
        val templateId = TemplateId(TemplateKey.of("letter"), CatalogId.default(TenantId(tenant)))
        val variant = VariantId(VariantKey.INITIAL, templateId)
        withMediator {
            CreateDocumentTemplate(templateId, "Letter").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("default"), themeCatalogKey = SYSTEM_CATALOG_KEY).execute()
            UpdateDraft(variant, TestTemplateBuilder.buildMinimal(name = "Letter")).execute()
            mediator.publishAndRelease(variant)
        }

        val systemVersion = withMediator { GetCatalog(tenant, SYSTEM_CATALOG_KEY).query()!!.installedReleaseVersion }
        val pins = jdbi.withHandle<Map<CatalogKey, String>, Exception> { dependencies.pinsOf(it, tenant, CatalogKey.DEFAULT, "1.0.0") }
        assertThat(pins).containsEntry(SYSTEM_CATALOG_KEY, systemVersion)
    }

    // Read-only: there is no query listing a catalog's releases with their fingerprint and flag together.
    private fun releaseRow(tenant: TenantKey, catalog: CatalogKey, version: String): Pair<String, Boolean> = jdbi.withHandle<Pair<String, Boolean>, Exception> { handle ->
        handle.createQuery("SELECT fingerprint, content_retained FROM catalog_releases WHERE tenant_key = :t AND catalog_key = :c AND version = :v")
            .bind("t", tenant)
            .bind("c", catalog)
            .bind("v", version)
            .map { rs, _ -> rs.getString("fingerprint") to rs.getBoolean("content_retained") }
            .one()
    }

    private fun releaseCount(tenant: TenantKey, catalog: CatalogKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT COUNT(*) FROM catalog_releases WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenant)
            .bind("c", catalog)
            .mapTo(Int::class.java)
            .one()
    }
}
