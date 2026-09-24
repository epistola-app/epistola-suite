// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.GLOBAL_ASSET_SCOPE
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
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
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.storage.ContentReaper
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * [ResourceRevisionStore] retains the content behind a release, so that a release is content and
 * not only a fingerprint — and so the content sweep can no longer take it away.
 */
class ResourceRevisionStoreTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var assetContentStore: AssetContentStore

    @Autowired
    private lateinit var reaper: ContentReaper

    @Test
    fun `releasing retains a revision for every resource`() {
        val catalog = authoredCatalog("rev-all")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), catalog), name = "T1").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("th2"), catalog), name = "T2").execute()

            assertThat(revisionCount(catalog.tenantKey)).isZero()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        assertThat(revisionKinds(catalog.tenantKey)).containsExactly("theme", "theme")
    }

    @Test
    fun `re-releasing unchanged content writes nothing, and one edit writes one revision`() {
        val catalog = authoredCatalog("rev-dedup")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("edited"), catalog), name = "Edited").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("untouched"), catalog), name = "Untouched").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }
        val afterFirst = revisionCount(catalog.tenantKey)
        assertThat(afterFirst).isEqualTo(2)

        withMediator {
            // Identical content: content-addressed, so there is nothing new to store.
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.1").execute()
        }
        assertThat(revisionCount(catalog.tenantKey)).isEqualTo(afterFirst)

        withMediator {
            UpdateTheme(id = ThemeId(ThemeKey.of("edited"), catalog), name = "Edited again").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.1.0").execute()
        }
        assertThat(revisionCount(catalog.tenantKey))
            .`as`("the edited theme is one new payload; the untouched one is reused")
            .isEqualTo(afterFirst + 1)
    }

    @Test
    fun `the same content digests the same in another tenant`() {
        val first = authoredCatalog("rev-det-a")
        val second = authoredCatalog("rev-det-b")

        for (catalog in listOf(first, second)) {
            withMediator {
                CreateTheme(id = ThemeId(ThemeKey.of("shared"), catalog), name = "Shared").execute()
                ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
            }
        }

        // Deliberately not shared across tenants — stored twice — but the same content, so the
        // same identity. A digest that depended on anything but the content would differ here.
        assertThat(digests(first.tenantKey)).isEqualTo(digests(second.tenantKey))
    }

    @Test
    fun `a template's variant model is a revision of its own`() {
        val catalog = authoredCatalog("rev-template")

        withMediator {
            val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalog))
            CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variant, emptyTemplate()).execute()
            PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()

            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        assertThat(revisionKinds(catalog.tenantKey)).contains(TEMPLATE_MODEL_KIND, "template")
        // The model lives in its own row, and the template payload points at it rather than
        // carrying it — which is what stops one variant's edit rewriting all of them.
        assertThat(templatePayload(catalog.tenantKey)).contains(REVISION_REF_FIELD)
    }

    @Test
    fun `bytes a release retained survive the image being deleted and the sweep running`() {
        val catalog = authoredCatalog("rev-roots")
        val content = UUID.randomUUID().toString().toByteArray()
        val hash = sha256Hex(content)

        val assetId = withMediator {
            val id = UploadAsset(
                tenantId = catalog.tenantKey,
                name = "held.png",
                mediaType = AssetMediaType.PNG,
                content = content,
                width = 1,
                height = 1,
                catalogKey = catalog.key,
            ).execute().id
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
            id
        }

        assertThat(binaryHashes(catalog.tenantKey))
            .`as`("the release recorded which bytes it needs")
            .contains(hash)

        withMediator { DeleteAsset(catalog.tenantKey, assetId).execute() }
        backdateBlob(GLOBAL_ASSET_SCOPE, hash)
        reaper.reap()

        assertThat(assetContentStore.exists(GLOBAL_ASSET_SCOPE, hash))
            .`as`("no live asset points at these bytes, but a release still names them")
            .isTrue()
    }

    @Test
    fun `the schema refuses to delete bytes a revision needs, whatever the sweep does`() {
        val catalog = authoredCatalog("rev-fk")
        val content = UUID.randomUUID().toString().toByteArray()
        val hash = sha256Hex(content)

        withMediator {
            UploadAsset(
                tenantId = catalog.tenantKey,
                name = "held.png",
                mediaType = AssetMediaType.PNG,
                content = content,
                width = 1,
                height = 1,
                catalogKey = catalog.key,
            ).execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        // The reaper's reachability rule is one thing that keeps these bytes; the foreign key is
        // another, and it holds even if that rule is ever wrong. Deleting directly is the only way
        // to ask the schema the question, since no command will.
        assertThatThrownBy {
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate("DELETE FROM asset_content WHERE scope = :s AND content_hash = :h")
                    .bind("s", GLOBAL_ASSET_SCOPE)
                    .bind("h", hash)
                    .execute()
            }
        }.hasMessageContaining("fk_revision_binaries_content")
    }

    private fun emptyTemplate(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
        themeRef = ThemeRef.Inherit,
    )

    private fun authoredCatalog(slug: String): CatalogId {
        val tenant = createTenant(slug)
        val catalogKey = CatalogKey.of(slug)
        withMediator { CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = slug).execute() }
        return CatalogId(catalogKey, TenantId(tenant.id))
    }

    private fun revisionCount(tenant: TenantKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM resource_revisions WHERE tenant_key = :t")
            .bind("t", tenant).mapTo(Int::class.java).one()
    }

    private fun revisionKinds(tenant: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT kind FROM resource_revisions WHERE tenant_key = :t ORDER BY kind")
            .bind("t", tenant).mapTo(String::class.java).list()
    }

    private fun digests(tenant: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT digest FROM resource_revisions WHERE tenant_key = :t ORDER BY digest")
            .bind("t", tenant).mapTo(String::class.java).list()
    }

    private fun templatePayload(tenant: TenantKey): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT payload::text FROM resource_revisions WHERE tenant_key = :t AND kind = 'template'")
            .bind("t", tenant).mapTo(String::class.java).one()
    }

    private fun binaryHashes(tenant: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT content_hash FROM revision_binaries WHERE tenant_key = :t")
            .bind("t", tenant).mapTo(String::class.java).list()
    }

    /**
     * Raw SQL: no command sets `asset_content.created_at`, and the reaper skips blobs inside its
     * grace window, so aging one is the only way to make it a sweep candidate at all.
     */
    private fun backdateBlob(scope: String, hash: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE asset_content SET created_at = now() - interval '5 years' WHERE scope = :s AND content_hash = :h")
            .bind("s", scope).bind("h", hash).execute()
    }
}
