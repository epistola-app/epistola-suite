package app.epistola.suite.tenantbackup

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Tenant isolation: restoring tenant A must never touch tenant B. The restore's **delete-absent**
 * phase scopes by `tenant_key` (regular tables) and by key prefix (`content_store` blobs); this
 * proves a bystander tenant's catalogs, template versions, assets, blobs, and feature toggles all
 * survive a neighbour's restore byte-for-byte.
 */
class CrossTenantIsolationIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Test
    fun `restoring one tenant leaves another tenant's data untouched`() {
        // Tenant B — the bystander: its own catalog, template (→ versions), asset blob, and toggle.
        val b = createTenant("Bystander B")
        val bMain = CatalogKey.of("main")
        val bCatalogId = CatalogId(bMain, TenantId(b.id))
        val bBytes = ByteArray(400) { (it % 256).toByte() }
        val bAssetKey =
            withMediator {
                CreateCatalog(tenantKey = b.id, id = bMain, name = "B Main").execute()
                CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), bCatalogId), name = "B Invoice").execute()
                SaveFeatureToggle(tenantKey = b.id, featureKey = KnownFeatures.SUPPORT_BACKUPS, enabled = true).execute()
                UploadAsset(
                    tenantId = b.id,
                    name = "b-logo.png",
                    mediaType = AssetMediaType.PNG,
                    content = bBytes,
                    width = 1,
                    height = 1,
                    catalogKey = bMain,
                ).execute().id
            }
        val bBlobKey = "assets/${b.id.value}/${bAssetKey.value}"
        val bBefore = snapshotTenant(b.id.value, bBlobKey)
        assertThat(bBefore.values).allSatisfy { assertThat(it).isNotNull() } // sanity: B actually has data

        // Tenant A — backed up, then diverged and restored.
        val a = createTenant("Subject A")
        val aMain = CatalogKey.of("main")
        withMediator { CreateCatalog(tenantKey = a.id, id = aMain, name = "A Main").execute() }
        val backup = withMediator { BuildTenantBackup(a.id).execute()!! }
        // Diverge A so the delete-absent phase actually runs during restore.
        withMediator { CreateCatalog(tenantKey = a.id, id = CatalogKey.of("stray"), name = "Stray").execute() }

        withMediator { RestoreTenantBackup(a.id, backup.bytes).execute() }

        // A is restored (its post-backup divergence dropped) …
        withMediator {
            assertThat(ListCatalogs(a.id).query().map { it.id.value }).contains("main").doesNotContain("stray")
        }
        // … and B is byte-for-byte untouched.
        assertThat(snapshotTenant(b.id.value, bBlobKey)).isEqualTo(bBefore)
    }

    /** Row counts of B's tenant-scoped tables plus its asset blob bytes — the "did anything change?" probe. */
    private fun snapshotTenant(
        tenantKey: String,
        blobKey: String,
    ): Map<String, Any?> = jdbi.withHandle<Map<String, Any?>, Exception> { h ->
        fun count(table: String): Int = h.createQuery("SELECT count(*) FROM $table WHERE tenant_key = :tk").bind("tk", tenantKey).mapTo(Int::class.java).one()
        mapOf(
            "catalogs" to count("catalogs"),
            "template_versions" to count("template_versions"),
            "assets" to count("assets"),
            "feature_toggles" to count("feature_toggles"),
            "blob" to
                h
                    .createQuery("SELECT content FROM content_store WHERE key = :k")
                    .bind("k", blobKey)
                    .map { rs, _ -> rs.getBytes("content")?.toList() }
                    .findOne()
                    .orElse(null),
        )
    }
}
