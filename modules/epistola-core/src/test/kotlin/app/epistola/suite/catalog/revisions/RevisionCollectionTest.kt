// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ForgetReleaseContent
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UnregisterCatalog
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Reclaiming what releases hold.
 *
 * Nothing deleted a revision. Not a sweep, not a command, not a cascade short of dropping the whole
 * tenant — so content accumulated for the life of an installation, and `revision_binaries` went on
 * holding the bytes it named against the content sweep. Two moments make a revision unreachable:
 * forgetting one release's content, and deleting a catalog, whose releases cascade away with it.
 * Both now collect, and these tests are what say so.
 *
 * The case that makes it more than a `DELETE` is a template: its revision names its models by
 * reference, so a model is reachable only through its parent. Collecting what no release entry names
 * *directly* would take every model with it, and the round trip would still pass.
 */
class RevisionCollectionTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `forgetting a release drops its content and keeps the release`() {
        val tenant = createTenant("Forget Content")
        val key = CatalogKey.of("forget-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Forget cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0", notes = "first").execute()

            assertThat(revisionCount(tenant.id)).`as`("released content is retained").isGreaterThan(0)

            assertThat(ForgetReleaseContent(tenant.id, key, "1.0.0").execute()).isTrue()

            assertThat(revisionCount(tenant.id)).`as`("and collected once nothing reaches it").isZero()
            assertThat(entryCount(tenant.id)).isZero()
        }

        // The release itself survives, which is the whole point: the history and the fingerprint
        // stay as the evidence of what that version was, for whoever installed it.
        val release = releaseRow(tenant.id.value, "forget-cat", "1.0.0")
        assertThat(release).`as`("the release row is still there").isNotNull()
        assertThat(release!!["content_retained"]).isEqualTo(false)
        assertThat(release["notes"]).isEqualTo("first")
        assertThat(release["fingerprint"] as String).isNotBlank()
    }

    @Test
    fun `forgetting is honest about having nothing to forget`() {
        val tenant = createTenant("Forget Twice")
        val key = CatalogKey.of("twice-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Twice cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()

            assertThat(ForgetReleaseContent(tenant.id, key, "1.0.0").execute()).isTrue()
            assertThat(ForgetReleaseContent(tenant.id, key, "1.0.0").execute())
                .`as`("a second click collected nothing, and says so")
                .isFalse()
            assertThat(ForgetReleaseContent(tenant.id, key, "9.9.9").execute())
                .`as`("a version that was never released")
                .isFalse()
        }
    }

    /**
     * The reason this is a graph walk. A template revision names its models through
     * `revisionDigest`; no release entry names a model directly. Collecting by "nothing in
     * release_entries names it" would delete every model and leave each template unassemblable —
     * and a round-trip test of the *other* release would still pass, because it has its own.
     */
    @Test
    fun `a release that keeps its content keeps the models its templates reference`() {
        val tenant = createTenant("Forget Shared")
        val key = CatalogKey.of("shared-cat")
        val catalog = CatalogId(key, TenantId(tenant.id))
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Shared cat").execute()
            // Published, not merely created: a release takes published versions only, so a draft
            // template carries no model and would write no model revision to reason about.
            publishTemplate(TemplateId(TemplateKey.of("invoice"), catalog))
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()

            publishTemplate(TemplateId(TemplateKey.of("letter"), catalog))
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.1.0").execute()

            val models = revisionCount(tenant.id, kind = TEMPLATE_MODEL_KIND)
            assertThat(models).`as`("a template's model is a revision of its own").isGreaterThan(0)

            ForgetReleaseContent(tenant.id, key, "1.0.0").execute()

            assertThat(revisionCount(tenant.id, kind = TEMPLATE_MODEL_KIND))
                .`as`("1.1.0 still holds the invoice, so its model is still reachable")
                .isEqualTo(models)
        }

        // And 1.1.0 is still readable as released, which is what those models are for.
        withMediator {
            assertThat(ReleaseEntryStoreProbe(jdbi).entryCount(tenant.id, "shared-cat", "1.1.0")).isGreaterThan(0)
        }
    }

    /**
     * The live leak this fixes. Deleting a catalog cascades its releases away, and their entries
     * with them, leaving the revisions unreachable — and `revision_binaries` still naming the bytes,
     * so the content sweep would hold them for ever. Before the collector, deleting a catalog made
     * its content permanently unreclaimable.
     */
    @Test
    fun `deleting a catalog collects the revisions its releases held`() {
        val tenant = createTenant("Delete Catalog")
        val key = CatalogKey.of("doomed-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Doomed cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()

            assertThat(revisionCount(tenant.id)).isGreaterThan(0)

            UnregisterCatalog(tenantKey = tenant.id, catalogKey = key).execute()

            assertThat(revisionCount(tenant.id))
                .`as`("the catalog's content went with it, instead of outliving it unreachably")
                .isZero()
            assertThat(binaryCount(tenant.id)).isZero()
        }
    }

    /**
     * What the reclaim is actually for. A release holds an image's bytes through
     * `revision_binaries`, which is what stops the content sweep taking them when the asset is
     * deleted from the working copy. Forgetting the release releases that hold, so the bytes become
     * collectable — the sweep itself still owns when.
     */
    @Test
    fun `forgetting a release releases the hold on the bytes it kept`() {
        val tenant = createTenant("Forget Blobs")
        val key = CatalogKey.of("blob-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Blob cat").execute()
            val asset = UploadAsset(
                tenantId = tenant.id,
                name = "held.png",
                mediaType = AssetMediaType.PNG,
                content = ByteArray(256) { (it % 251).toByte() },
                width = 1,
                height = 1,
                catalogKey = key,
            ).execute().id
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()
            DeleteAsset(tenant.id, asset).execute()

            assertThat(binaryCount(tenant.id))
                .`as`("only the release holds those bytes now")
                .isGreaterThan(0)

            ForgetReleaseContent(tenant.id, key, "1.0.0").execute()

            assertThat(binaryCount(tenant.id))
                .`as`("nothing holds them any more, so the sweep may take them")
                .isZero()
        }
    }

    /** Creates a template, gives it a document and publishes it, so a release can carry a model. */
    private fun publishTemplate(templateId: TemplateId) {
        val variant = VariantId(VariantKey.INITIAL, templateId)
        CreateDocumentTemplate(templateId, templateId.key.value).execute().withRequiredDataExample()
        UpdateDraft(
            variant,
            TemplateDocument(
                modelVersion = 1,
                root = "root",
                nodes = mapOf("root" to Node(id = "root", type = "root")),
                slots = emptyMap(),
                themeRef = ThemeRef.Inherit,
            ),
        ).execute()
        PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()
    }

    private fun revisionCount(tenantKey: TenantKey, kind: String? = null): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            "SELECT count(*) FROM resource_revisions WHERE tenant_key = :t AND (:kind IS NULL OR kind = :kind)",
        )
            .bind("t", tenantKey)
            .bind("kind", kind)
            .mapTo(Int::class.java)
            .one()
    }

    private fun entryCount(tenantKey: TenantKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM release_entries WHERE tenant_key = :t")
            .bind("t", tenantKey).mapTo(Int::class.java).one()
    }

    private fun binaryCount(tenantKey: TenantKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM revision_binaries WHERE tenant_key = :t")
            .bind("t", tenantKey).mapTo(Int::class.java).one()
    }

    /**
     * Raw SQL: asserting the release row *survived* means reading it directly. A read query would
     * only tell us what it reports, and what is in question is whether the row is still there.
     */
    private fun releaseRow(tenantKey: String, catalogKey: String, version: String): Map<String, Any?>? = jdbi.withHandle<Map<String, Any?>?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT content_retained, notes, fingerprint FROM catalog_releases
                WHERE tenant_key = :t AND catalog_key = :c AND version = :v
                """,
        )
            .bind("t", tenantKey).bind("c", catalogKey).bind("v", version)
            .map { rs, _ ->
                mapOf(
                    "content_retained" to rs.getBoolean("content_retained"),
                    "notes" to rs.getString("notes"),
                    "fingerprint" to rs.getString("fingerprint"),
                )
            }
            .findOne()
            .orElse(null)
    }

    private class ReleaseEntryStoreProbe(private val jdbi: Jdbi) {
        fun entryCount(tenantKey: TenantKey, catalogKey: String, version: String): Int = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(
                "SELECT count(*) FROM release_entries WHERE tenant_key = :t AND catalog_key = :c AND version = :v",
            )
                .bind("t", tenantKey).bind("c", catalogKey).bind("v", version)
                .mapTo(Int::class.java)
                .one()
        }
    }
}
