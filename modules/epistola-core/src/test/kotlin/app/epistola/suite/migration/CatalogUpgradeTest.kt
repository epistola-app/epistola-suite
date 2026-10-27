// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.GetReleasedResource
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.upgrade.CatalogUpgradeRunner
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
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
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.queries.GetGenerationReadiness
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.stencils.texts
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.template.model.TemplateDocument
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * The 2.0 upgrade of a 1.x catalog ([CatalogUpgradeRunner]): it declares what its content references,
 * since 1.x had no declarations, and gets a first release cut from what was last published, since no
 * 1.x release kept its content. The 1.x rows are written as 1.x wrote them; no command of today
 * writes template or stencil versions.
 */
class CatalogUpgradeTest : IntegrationTestBase() {

    @Autowired
    private lateinit var upgrade: CatalogUpgradeRunner

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun tenant(name: String): TenantKey = createTenant(name).id

    private fun catalog(tenant: TenantKey, key: CatalogKey): CatalogId = withMediator {
        CreateCatalog(tenant, key, key.value).execute()
        testCatalog(TenantId(tenant), key)
    }

    /**
     * What the upgrade migration does for every authored catalog of a 1.x installation, recording its
     * highest 1.x release ([continuesAfter]) before it removes the 1.x releases.
     */
    private fun queue(catalog: CatalogId, continuesAfter: String? = null) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("INSERT INTO catalog_upgrade_queue (tenant_key, catalog_id, continues_after) VALUES (:t, ${catalogAt("t", "c")}, :after)")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bind("after", continuesAfter)
            .execute()
    }

    /** As the cluster scheduler runs an install-wide task: a mediator, and no principal. */
    private fun runUpgrade() = MediatorContext.runWithMediator(mediator) { upgrade.run() }

    private fun versions(catalog: CatalogId) = withMediator { ListCatalogReleases(catalog).query() }.map { it.label to it.status }

    private fun queueRow(catalog: CatalogId): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery("SELECT COALESCE(last_error, 'queued') FROM catalog_upgrade_queue WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }

    private fun document(text: String): TemplateDocument = stencilDocument {
        text("t", text)
        build("t")
    }

    /**
     * What a 1.x variant or stencil last published, as the upgrade migration keeps it for the first
     * release (`catalog_upgrade_published`): a variant's model under its key, a stencil's content.
     */
    private fun published1x(kind: String, ownerId: java.util.UUID, part: String, model: TemplateDocument) = jdbi.useHandle<Exception> { handle ->
        val payload = mapOf((if (kind == "variant") "templateModel" else "content") to model)
        handle.createUpdate(
            """
            INSERT INTO catalog_upgrade_published (tenant_key, resource_id, kind, part, payload)
            SELECT tenant_key, resource_id, :kind, :part, CAST(:payload AS JSONB) FROM catalog_resources WHERE resource_id = :owner
            """,
        )
            .bind("owner", ownerId)
            .bind("kind", kind)
            .bind("part", part)
            .bind("payload", objectMapper.writeValueAsString(payload))
            .execute()
    }

    private fun publishedRows(catalog: CatalogId): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            "SELECT count(*) FROM catalog_upgrade_published p JOIN catalog_resources r ON r.tenant_key = p.tenant_key AND r.resource_id = p.resource_id WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")}",
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .mapTo(Int::class.java)
            .one()
    }

    private fun resourceId(catalog: CatalogId, type: String, key: String): java.util.UUID = jdbi.withHandle<java.util.UUID, Exception> { handle ->
        handle.createQuery("SELECT resource_id FROM catalog_resources WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND resource_type = :type AND resource_key = :k")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bind("type", type)
            .bind("k", key)
            .mapTo(java.util.UUID::class.java)
            .one()
    }

    // ── Declarations ──

    @Test
    fun `the tenant home counts catalogs waiting for their upgrade until it has run`() {
        val tenant = tenant("upgrade-readiness")
        queue(catalog(tenant, CatalogKey.of("letters")))
        assertThat(withMediator { GetGenerationReadiness(tenant).query() }.catalogsUpgrading).isEqualTo(1)

        runUpgrade()

        assertThat(withMediator { GetGenerationReadiness(tenant).query() }.catalogsUpgrading).isZero()
    }

    @Test
    fun `a catalog that references system undeclared declares it, and is released`() {
        val catalog = catalog(tenant("upgrade-declares"), letters)
        withMediator {
            val theme = ThemeId(ThemeKey.of("brand"), catalog)
            CreateTheme(theme, "Brand").execute()
            UpdateTheme(theme, documentStyles = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value))).execute()
        }
        queue(catalog)

        runUpgrade()

        val declared = withMediator { ListCatalogDependencies(catalog).query() }
        assertThat(declared.map { it.catalogKey }).containsExactly(SYSTEM_CATALOG_KEY.value)
        assertThat(declared.single().releaseVersion).`as`("pinned to the installed system release").isNotNull()
        assertThat(versions(catalog)).containsExactly("Next release" to CatalogReleaseStatus.DRAFT, "v1.0.0" to CatalogReleaseStatus.RELEASED)
        assertThat(queueRow(catalog)).isNull()
    }

    // ── The first release ──

    @Test
    fun `a catalog released in 1_x continues its numbering, with one release and the next version`() {
        val catalog = catalog(tenant("upgrade-continues"), letters)
        val invoice = TemplateId(TemplateKey.of("invoice"), catalog)
        withMediator { CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample() }
        published1x("variant", resourceId(catalog, "template", "invoice"), VariantKey.INITIAL.value, document("published"))
        queue(catalog, continuesAfter = "1.3.0")

        runUpgrade()

        assertThat(versions(catalog)).containsExactly("Next release" to CatalogReleaseStatus.DRAFT, "v1.3.1" to CatalogReleaseStatus.RELEASED)
    }

    @Test
    fun `two catalogs with one key are each upgraded, continuing their own numbering`() {
        val tenant = tenant("upgrade-two-letters")
        val (acme, bureau) = withMediator {
            TestNamespaces.grant(tenant, "acme").execute()
            TestNamespaces.grant(tenant, "bureau").execute()
            listOf("acme", "bureau").map { namespace ->
                CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
                CatalogId(namespace, letters, TenantId(tenant)).also { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), it), "Invoice").execute().withRequiredDataExample() }
            }
        }
        published1x("variant", resourceId(acme, "template", "invoice"), VariantKey.INITIAL.value, document("acme"))
        published1x("variant", resourceId(bureau, "template", "invoice"), VariantKey.INITIAL.value, document("bureau"))
        queue(acme, continuesAfter = "1.3.0")
        queue(bureau, continuesAfter = "2.0.0")

        runUpgrade()

        assertThat(versions(acme)).containsExactly("Next release" to CatalogReleaseStatus.DRAFT, "v1.3.1" to CatalogReleaseStatus.RELEASED)
        assertThat(versions(bureau)).containsExactly("Next release" to CatalogReleaseStatus.DRAFT, "v2.0.1" to CatalogReleaseStatus.RELEASED)
        assertThat(queueRow(acme)).isNull()
        assertThat(queueRow(bureau)).isNull()
    }

    @Test
    fun `the first release holds what was last published, and the draft keeps the unpublished work`() {
        val catalog = catalog(tenant("upgrade-published"), letters)
        val invoice = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalog))
        val unpublished = TemplateId(TemplateKey.of("never-published"), catalog)
        withMediator {
            CreateDocumentTemplate(invoice.templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(invoice, document("draft work")).execute()
            CreateDocumentTemplate(unpublished, "Never published").execute().withRequiredDataExample()
        }
        published1x("variant", resourceId(catalog, "template", "invoice"), VariantKey.INITIAL.value, document("published"))
        queue(catalog)

        runUpgrade()

        val released = withMediator { GetReleasedResource(catalog, "1.0.0", "template", "invoice").query() } as TemplateResource
        assertThat(released.templateModel.texts()).containsExactly("published")
        assertThat(withMediator { GetReleasedResource(catalog, "1.0.0", "template", "never-published").query() })
            .`as`("a template never published is not released").isNull()
        assertThat(withMediator { GetVariantContent(invoice).query()!! }.texts()).`as`("the draft").containsExactly("draft work")
        assertThat(publishedRows(catalog)).`as`("what 1.x published goes once the first release holds it").isZero()
    }

    @Test
    fun `a stencil is released as its last published version`() {
        val catalog = catalog(tenant("upgrade-stencil"), letters)
        val stencil = StencilId(StencilKey.of("letterhead"), catalog)
        withMediator { CreateStencil(stencil, "Letterhead", content = document("stencil draft")).execute() }
        published1x("stencil", resourceId(catalog, "stencil", "letterhead"), "", document("stencil published"))
        queue(catalog)

        runUpgrade()

        val released = withMediator { GetReleasedResource(catalog, "1.0.0", "stencil", "letterhead").query() } as StencilResource
        assertThat(released.content.texts()).containsExactly("stencil published")
        assertThat(withMediator { GetStencil(stencil).query()!! }.content.texts()).containsExactly("stencil draft")
    }

    // ── Subscribed catalogs ──

    /**
     * A catalog a tenant subscribed to under 1.x: installed, with the publisher's legacy fingerprint,
     * and no release recorded, since 1.x kept none. The install is done with today's commands, then
     * its recorded release is removed and the legacy fingerprint put back, as 1.x left it.
     */
    private fun subscribedIn1x(tenant: TenantKey): CatalogId {
        val catalog = testCatalog(TenantId(tenant), CatalogKey.of("epistola-demo"))
        withMediator {
            RegisterCatalog(tenantKey = tenant, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant, catalog.key)).execute()
        }
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
                .bind("t", tenant).bindCatalog("c", testCatalog(tenant, catalog.key)).execute()
            // A 1.x subscribed catalog: typed so (the suite no longer writes the column), with a legacy fingerprint.
            handle.createUpdate("UPDATE catalogs SET installed_fingerprint = :fp, type = 'SUBSCRIBED' WHERE tenant_key = :t AND catalog_key = :c")
                .bind("fp", "0".repeat(64)).bind("t", tenant).bind("c", catalog.key).execute()
        }
        return catalog
    }

    private fun releases(catalog: CatalogId): List<Pair<String, String>> = jdbi.withHandle<List<Pair<String, String>>, Exception> { handle ->
        handle.createQuery("SELECT version, fingerprint FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", catalog.tenantKey).bindCatalog("c", catalog)
            .map { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list()
    }

    @Test
    fun `a subscribed catalog gets its installed release recorded, with a fingerprint that can be pinned`() {
        val subscribed = subscribedIn1x(tenant("upgrade-subscribed"))
        assertThat(releases(subscribed)).isEmpty()
        queue(subscribed)

        runUpgrade()

        val recorded = releases(subscribed)
        assertThat(recorded.map { it.first }).containsExactly("5.17.4")
        assertThat(recorded.single().second).`as`("qualified, so a dependent can lock to it").startsWith("v6:")
        assertThat(queueRow(subscribed)).isNull()
    }

    @Test
    fun `an authored catalog that uses a subscribed one is released after it is recorded, in one run`() {
        val tenantKey = tenant("upgrade-subscribed-dependent")
        val subscribed = subscribedIn1x(tenantKey)
        val letters = catalog(tenantKey, letters)
        val invoice = TemplateId(TemplateKey.of("invoice"), letters)
        withMediator {
            CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("corporate"), themeCatalog = testCatalog(invoice.tenantKey, subscribed.key)).execute()
        }
        published1x("variant", resourceId(letters, "template", "invoice"), VariantKey.INITIAL.value, document("published"))
        // 'epistola-demo' sorts first, but queue the dependent first to be sure the order comes from the rounds.
        queue(letters)
        queue(subscribed)

        runUpgrade()

        assertThat(versions(letters)).contains("v1.0.0" to CatalogReleaseStatus.RELEASED)
        assertThat(withMediator { ListCatalogDependencies(testCatalog(letters.tenantKey, letters.key)).query() }.map { it.catalogKey to it.releaseVersion })
            .contains("epistola-demo" to "5.17.4")
    }

    // ── Order and retries ──

    private fun lettersUsingSharedCodeList(tenant: TenantKey): Pair<CatalogId, CatalogId> {
        val sharedCatalog = catalog(tenant, shared)
        val lettersCatalog = catalog(tenant, letters)
        withMediator {
            val regions = CodeListId(CodeListKey.of("regions"), sharedCatalog)
            CreateCodeList(id = regions, displayName = "Regions", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("eu", "Europe"))).execute()
            CreateAttributeDefinition(id = AttributeId(AttributeKey.of("region"), lettersCatalog), displayName = "Region", codeListId = regions).execute()
        }
        return sharedCatalog to lettersCatalog
    }

    @Test
    fun `a catalog is released after the catalogs it depends on, in one run`() {
        val (sharedCatalog, lettersCatalog) = lettersUsingSharedCodeList(tenant("upgrade-order"))
        // 'letters' sorts first, so the first round cannot pin 'shared' yet.
        queue(lettersCatalog)
        queue(sharedCatalog)

        runUpgrade()

        assertThat(versions(sharedCatalog)).contains("v1.0.0" to CatalogReleaseStatus.RELEASED)
        assertThat(versions(lettersCatalog)).contains("v1.0.0" to CatalogReleaseStatus.RELEASED)
        assertThat(queueRow(lettersCatalog)).isNull()
    }

    @Test
    fun `a catalog whose dependency has no release stays queued with the reason, until it has one`() {
        val (sharedCatalog, lettersCatalog) = lettersUsingSharedCodeList(tenant("upgrade-retry"))
        queue(lettersCatalog)

        runUpgrade()

        assertThat(queueRow(lettersCatalog)).contains("uses drafts of other catalogs")
        assertThat(versions(lettersCatalog)).containsExactly("Next release" to CatalogReleaseStatus.DRAFT)

        queue(sharedCatalog)
        runUpgrade()

        assertThat(queueRow(lettersCatalog)).isNull()
        assertThat(versions(lettersCatalog)).contains("v1.0.0" to CatalogReleaseStatus.RELEASED)
    }

    /** A 1.x published variant on the `corporate` theme of catalog `ghost`, which the tenant does not have. */
    private fun publishedOnGhostTheme(catalog: CatalogId) {
        @Suppress("UNCHECKED_CAST")
        val model = objectMapper.readValue(objectMapper.writeValueAsString(document("published")), Map::class.java) as MutableMap<String, Any?>
        model["themeRef"] = mapOf("type" to "override", "themeKey" to "corporate", "catalogKey" to "ghost")
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_upgrade_published (tenant_key, resource_id, kind, part, payload)
                SELECT tenant_key, resource_id, 'variant', :part, CAST(:payload AS JSONB) FROM catalog_resources WHERE resource_id = :owner
                """,
            )
                .bind("owner", resourceId(catalog, "template", "invoice"))
                .bind("part", VariantKey.INITIAL.value)
                .bind("payload", objectMapper.writeValueAsString(mapOf("templateModel" to model)))
                .execute()
        }
    }

    @Test
    fun `a catalog whose published content uses a catalog the tenant does not have stays queued, naming that catalog`() {
        val catalog = catalog(tenant("upgrade-unknown"), letters)
        withMediator { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice").execute().withRequiredDataExample() }
        publishedOnGhostTheme(catalog)
        queue(catalog)

        runUpgrade()

        assertThat(queueRow(catalog)).`as`("not \"dependency names no namespace\"").contains("catalog 'ghost', which this tenant does not have")
        assertThat(versions(catalog)).containsExactly("Next release" to CatalogReleaseStatus.DRAFT)
    }

    @Test
    fun `a catalog created under 2_0 is not queued, so nothing is declared or released behind its author`() {
        val catalog = catalog(tenant("upgrade-not-queued"), letters)
        withMediator {
            val theme = ThemeId(ThemeKey.of("brand"), catalog)
            CreateTheme(theme, "Brand").execute()
            UpdateTheme(theme, documentStyles = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value))).execute()
        }

        runUpgrade()

        // Only what its author's own write declared, as it used the system catalog's font: nothing added behind it.
        assertThat(withMediator { ListCatalogDependencies(catalog).query() }.map { it.catalogKey }).containsExactly(SYSTEM_CATALOG_KEY.value)
        assertThat(versions(catalog)).containsExactly("Next release" to CatalogReleaseStatus.DRAFT)
    }
}
