// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.MarkTemplateReady
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The drafts of one catalog (contract ADR 0002, amendment 2): its next version, where everyday work
 * goes, and long-term work next to it. Each is an independent copy with a fixed base, a message that
 * names a draft ([CatalogVersionScoped]) reads and writes that draft, and only the next version is
 * released, numbered when it is.
 */
class SeveralDraftsTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var targeting: CatalogVersionTargeting

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: TenantKey) = CatalogId(letters, TenantId(tenant))

    private fun brand(tenant: TenantKey) = ThemeId(ThemeKey.of("brand"), catalogId(tenant))

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant))

    private fun at(tenant: TenantKey, draft: DraftRef) = CatalogVersionRef(tenant, letters, draft)

    private fun next(tenant: TenantKey) = at(tenant, DraftRef.Next)

    /** `letters` with a brand theme and an invoice on it, released as 1.0.0; its next version continues on it. */
    private fun released(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = letters).execute()
        mediator.releaseNext(catalogId(tenant))
        tenant
    }

    /** Starts a draft called [name], by default a copy of the next version; the ref that names it. */
    private fun work(tenant: TenantKey, name: String, from: String? = DraftRef.NEXT): DraftRef = withMediator {
        DraftRef.Id(CreateCatalogDraft(tenant, letters, name, from).execute().versionId)
    }

    private fun themeName(tenant: TenantKey, draft: DraftRef?) = withMediator { GetTheme(brand(tenant), catalogVersion = draft?.let { at(tenant, it) }).query()?.name }

    private fun versions(tenant: TenantKey) = withMediator { ListCatalogVersions(tenant, letters).query() }.map { it.label to it.status }

    private fun versionId(tenant: TenantKey, draft: DraftRef): UUID = when (draft) {
        is DraftRef.Id -> jdbi.withHandle<UUID, Exception> { handle ->
            handle.createQuery("SELECT version_id FROM catalog_versions WHERE tenant_key = :t AND version_key = :k")
                .bind("t", tenant).bind("k", draft.key).mapTo(UUID::class.java).one()
        }

        DraftRef.Next -> jdbi.withHandle<UUID, Exception> { handle ->
            handle.createQuery(
                "SELECT v.version_id FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id " +
                    "WHERE c.tenant_key = :t AND c.catalog_key = :c AND v.is_next",
            ).bind("t", tenant).bind("c", letters).mapTo(UUID::class.java).one()
        }
    }

    /** Every resource part's content digest a version holds: equal maps are equal content. */
    private fun digests(tenant: TenantKey, draft: DraftRef): Map<Pair<UUID, String>, String> = jdbi.withHandle<Map<Pair<UUID, String>, String>, Exception> { handle ->
        handle.createQuery("SELECT resource_id, part, digest FROM resource_content_digests WHERE tenant_key = :t AND version_id = :v")
            .bind("t", tenant).bind("v", versionId(tenant, draft))
            .map { rs, _ -> (rs.getObject("resource_id", UUID::class.java) to rs.getString("part")) to rs.getString("digest") }
            .list().toMap()
    }

    private fun identityExists(tenant: TenantKey, type: String, key: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            "SELECT EXISTS (SELECT 1 FROM catalog_resources r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id " +
                "WHERE r.tenant_key = :t AND c.catalog_key = :c AND r.resource_type = :type AND r.resource_key = :key)",
        ).bind("t", tenant).bind("c", letters).bind("type", type).bind("key", key).mapTo(Boolean::class.java).one()
    }

    private fun rowsIn(tenant: TenantKey, draft: DraftRef): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            """
            SELECT (SELECT count(*) FROM themes WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM document_templates WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM template_variants WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM code_lists WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM code_list_entries WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM variant_attribute_definitions WHERE tenant_key = :t AND version_id = :v)
            """,
        ).bind("t", tenant).bind("v", versionId(tenant, draft)).mapTo(Int::class.java).one()
    }

    @Test
    fun `the next version and long-term work are edited independently, and a caller that names none means the next version`() {
        val tenant = released("drafts-independent")
        val redesign = work(tenant, "Redesign")

        withMediator { UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, redesign)).execute() }
        withMediator { UpdateTheme(brand(tenant), name = "Brand fixed", catalogVersion = next(tenant)).execute() }

        assertThat(themeName(tenant, redesign)).isEqualTo("Brand redesigned")
        assertThat(themeName(tenant, DraftRef.Next)).isEqualTo("Brand fixed")
        // Not the newest draft: the next version, whatever else is open.
        assertThat(themeName(tenant, null)).isEqualTo("Brand fixed")
        // One resource, one identity, in both.
        assertThat(digests(tenant, DraftRef.Next).keys.map { it.first }.toSet()).isEqualTo(digests(tenant, redesign).keys.map { it.first }.toSet())
        // A reference resolves in the version being read.
        val template = withMediator { GetDocumentTemplate(invoice(tenant), catalogVersion = at(tenant, redesign)).query()!! }
        assertThat(template.themeKey?.value).isEqualTo("brand")
        assertThat(versions(tenant)).containsExactly(
            CatalogDraft.NEXT_LABEL to CatalogVersionStatus.DRAFT,
            "Redesign" to CatalogVersionStatus.DRAFT,
            "v1.0.0" to CatalogVersionStatus.RELEASED,
        )
    }

    @Test
    fun `a draft started from the next version is a copy of it, unreleased changes and ready marks included, on its base`() {
        val tenant = released("drafts-from-next")
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand, edited").execute()
            val colours = CodeListId(CodeListKey.of("colours"), catalogId(tenant))
            CreateCodeList(colours, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"), CodeListEntry("blue", "Blue", 1))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("colour"), catalogId(tenant)), "Colour", codeListId = colours).execute()
            MarkTemplateReady(invoice(tenant)).execute()
        }

        val next = withMediator { GetCatalogDraft(tenant, letters).query()!! }
        val created = withMediator { CreateCatalogDraft(tenant, letters, "Redesign", from = DraftRef.NEXT).execute() }
        val redesign = DraftRef.Id(created.versionId)

        assertThat(created.isNext).isFalse()
        assertThat(created.version).`as`("numbered only when released").isNull()
        assertThat(created.base).isEqualTo("1.0.0")
        assertThat(created.createdFrom).`as`("the draft it is a copy of, by its id").isEqualTo(next.versionId)
        assertThat(digests(tenant, redesign)).isEqualTo(digests(tenant, DraftRef.Next)).isNotEmpty()
        assertThat(rowsIn(tenant, redesign)).isEqualTo(rowsIn(tenant, DraftRef.Next))
        assertThat(withMediator { GetVariantSummaries(invoice(tenant), catalogVersion = at(tenant, redesign)).query() }.map { it.ready }).containsOnly(true)
    }

    @Test
    fun `a draft started from nothing named is a copy of the highest release, on it`() {
        val tenant = released("drafts-default-from")
        withMediator { UpdateTheme(brand(tenant), name = "Brand, edited").execute() }

        val created = withMediator { CreateCatalogDraft(tenant, letters, "Fixes").execute() }

        assertThat(created.base).isEqualTo("1.0.0")
        assertThat(created.createdFrom).isNull()
        assertThat(themeName(tenant, DraftRef.Id(created.versionId))).`as`("the release's, not the next version's edit").isEqualTo("Brand")
    }

    @Test
    fun `long-term work started from a release holds what the release holds, on that release`() {
        val tenant = released("drafts-from-release")
        val releasedDigests = digests(tenant, DraftRef.Next)
        withMediator { UpdateTheme(brand(tenant), name = "Brand, edited later").execute() }

        val fromRelease = withMediator { CreateCatalogDraft(tenant, letters, "From one", from = "1.0.0").execute() }
        val ref = DraftRef.Id(fromRelease.versionId)

        assertThat(fromRelease.base).isEqualTo("1.0.0")
        assertThat(fromRelease.createdFrom).isNull()
        assertThat(themeName(tenant, ref)).isEqualTo("Brand")
        // Against its base, a copy of the base has changed nothing.
        val changes = withMediator { GetCatalogResourceChanges(tenant, letters, catalogVersion = at(tenant, ref)).query() }
        assertThat(changes.resources.map { it.state }).containsOnly(CatalogResourceState.RELEASED)
        // The same resources, under the same identities. (Stored rows may differ from the release in
        // what its canonical form leaves out, such as a data example's generated id, so the canonical
        // comparison above is the content check.)
        assertThat(digests(tenant, ref).keys).isEqualTo(releasedDigests.keys)
    }

    @Test
    fun `a name in use or that reads as the next version, an unknown release, an unknown draft and a subscribed catalog are refused`() {
        val tenant = released("drafts-refused")
        work(tenant, "Redesign")
        assertThatThrownBy { work(tenant, "redesign") }.`as`("names tell drafts apart, whatever their case").isInstanceOf(CatalogDraftNameInUseException::class.java)
        assertThatThrownBy { work(tenant, "Next version") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { work(tenant, "  ") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { work(tenant, "Other", from = "1.5.0") }.isInstanceOf(CatalogVersionNotFoundException::class.java)
        // A message naming a draft runs on it, so one that does not exist is refused.
        assertThatThrownBy { themeName(tenant, DraftRef.Id("nosuchdraft")) }.isInstanceOf(CatalogVersionNotFoundException::class.java)

        val subscribed = CatalogKey.of("epistola-demo")
        withMediator {
            RegisterCatalog(tenantKey = tenant, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant, catalogKey = subscribed).execute()
        }
        assertThatThrownBy { withMediator { CreateCatalogDraft(tenant, subscribed, "Mine").execute() } }.isInstanceOf(CatalogReadOnlyException::class.java)
        assertThatThrownBy { withMediator { GetTheme(ThemeId(ThemeKey.of("brand"), CatalogId(subscribed, TenantId(tenant))), catalogVersion = CatalogVersionRef(tenant, subscribed, DraftRef.Next)).query() } }
            .`as`("a subscribed catalog has no next version").isInstanceOf(CatalogVersionNotFoundException::class.java)
    }

    @Test
    fun `long-term work can be renamed, the next version cannot`() {
        val tenant = released("drafts-rename")
        val redesign = work(tenant, "Redesign") as DraftRef.Id
        work(tenant, "Other")

        val renamed = withMediator { RenameCatalogDraft(tenant, letters, redesign, "Redesign 2027").execute() }

        assertThat(renamed.name).isEqualTo("Redesign 2027")
        assertThatThrownBy { withMediator { RenameCatalogDraft(tenant, letters, redesign, "other").execute() } }.isInstanceOf(CatalogDraftNameInUseException::class.java)
        assertThatThrownBy { withMediator { RenameCatalogDraft(tenant, letters, DraftRef.Next, "Mine").execute() } }.isInstanceOf(CatalogDraftIsNextException::class.java)
    }

    @Test
    fun `discarding long-term work deletes only its content, and a resource keeps its identity while another draft or a release holds it`() {
        val tenant = released("drafts-delete")
        val redesign = work(tenant, "Redesign")
        val kept = rowsIn(tenant, DraftRef.Next)

        withMediator { DeleteTheme(brand(tenant), catalogVersion = at(tenant, redesign)).execute() }
        assertThat(themeName(tenant, redesign)).isNull()
        assertThat(themeName(tenant, DraftRef.Next)).isEqualTo("Brand")
        assertThat(identityExists(tenant, "theme", "brand")).isTrue()

        withMediator { DeleteCatalogDraft(tenant, letters, redesign).execute() }
        assertThat(versions(tenant).map { it.first }).containsExactly(CatalogDraft.NEXT_LABEL, "v1.0.0")
        assertThat(rowsIn(tenant, DraftRef.Next)).isEqualTo(kept)
        assertThat(identityExists(tenant, "template", "invoice")).isTrue()
        assertThatThrownBy { withMediator { DeleteCatalogDraft(tenant, letters, redesign).execute() } }.isInstanceOf(CatalogVersionNotFoundException::class.java)

        // With no draft left holding it, the identity stays while a release does (1.0.0 holds 'brand')...
        withMediator { DeleteTheme(brand(tenant), catalogVersion = next(tenant)).execute() }
        assertThat(identityExists(tenant, "theme", "brand")).isTrue()
        // ...and goes when nothing holds it: a theme that was never released.
        val draftOnly = ThemeId(ThemeKey.of("draft-only"), catalogId(tenant))
        withMediator {
            CreateTheme(draftOnly, "Draft only", catalogVersion = next(tenant)).execute()
            DeleteTheme(draftOnly, catalogVersion = next(tenant)).execute()
        }
        assertThat(identityExists(tenant, "theme", "draft-only")).isFalse()
    }

    @Test
    fun `discarding the next version throws its changes away and starts it again on the latest release`() {
        val tenant = released("drafts-discard-next")
        val before = versionId(tenant, DraftRef.Next)
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand, edited").execute()
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalogId(tenant)), "Seasonal").execute()
        }

        withMediator { DeleteCatalogDraft(tenant, letters, DraftRef.Next).execute() }

        val reopened = withMediator { GetCatalogDraft(tenant, letters).query()!! }
        assertThat(reopened.versionId).`as`("a fresh draft").isNotEqualTo(before)
        assertThat(reopened.base).isEqualTo("1.0.0")
        assertThat(themeName(tenant, null)).isEqualTo("Brand")
        assertThat(withMediator { GetTheme(ThemeId(ThemeKey.of("seasonal"), catalogId(tenant))).query() }).isNull()
        assertThat(withMediator { GetCatalogResourceChanges(tenant, letters).query() }.resources.map { it.state }).containsOnly(CatalogResourceState.RELEASED)
    }

    @Test
    fun `releasing the next version numbers it, continues it on the release, and puts another draft behind it`() {
        val tenant = released("drafts-release-next")
        val redesign = work(tenant, "Redesign")
        val nextBefore = versionId(tenant, DraftRef.Next)
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand, fixed").execute()
            MarkCatalogReady(tenant, letters).execute()
        }

        val result = withMediator { ReleaseCatalogVersion(tenant, letters).execute() }

        assertThat(result.version).`as`("a fix: the next patch").isEqualTo("1.0.1")
        assertThat(result.draftsBehind).containsExactly("Redesign")
        assertThat(versionId(tenant, DraftRef.Next)).`as`("the same draft continues").isEqualTo(nextBefore)
        assertThat(withMediator { GetCatalogDraft(tenant, letters).query()!! }.base).isEqualTo("1.0.1")
        assertThat(versions(tenant)).containsExactly(
            CatalogDraft.NEXT_LABEL to CatalogVersionStatus.DRAFT,
            "Redesign" to CatalogVersionStatus.DRAFT,
            "v1.0.1" to CatalogVersionStatus.RELEASED,
            "v1.0.0" to CatalogVersionStatus.RELEASED,
        )
        assertThat(withMediator { GetReleasedResource(tenant, letters, "1.0.1", "theme", "brand").query() }?.name).isEqualTo("Brand, fixed")
        assertThat(themeName(tenant, DraftRef.Next)).isEqualTo("Brand, fixed")
        assertThat(themeName(tenant, redesign)).isEqualTo("Brand")
        assertThat(withMediator { GetCatalog(tenant, letters).query()!! }.releasedVersion).isEqualTo("1.0.1")

        val work = withMediator { GetCatalogDraft(tenant, letters, redesign, withBehind = true).query()!! }
        assertThat(work.behind.map { it.version }).`as`("its number is 1.0.2 now 1.0.1 is taken").containsExactly("1.0.1")
        assertThat(work.behind.single().changes).containsExactly(ReleasedChange("theme", "brand", "modified"))
        assertThat(withMediator { GetCatalogDraft(tenant, letters, withBehind = true).query()!! }.behind).`as`("the next version continues on it").isEmpty()

        withMediator { MarkCatalogReady(tenant, letters, catalogVersion = at(tenant, redesign)).execute() }
        assertThatThrownBy { withMediator { ReleaseCatalogVersion(tenant, letters, catalogVersion = at(tenant, redesign)).execute() } }
            .`as`("any draft is released, once what it is behind is acknowledged")
            .isInstanceOf(CatalogVersionBehindException::class.java)
            .hasMessageContaining("1.0.1")
        val redesigned = withMediator { ReleaseCatalogVersion(tenant, letters, acknowledgedBehind = setOf("1.0.1"), catalogVersion = at(tenant, redesign)).execute() }
        assertThat(redesigned.version).`as`("the next free fix after its base").isEqualTo("1.0.2")
    }

    @Test
    fun `a resource held only by the next version and a release keeps its identity when the draft drops it, and comes back under it`() {
        val tenant = released("drafts-identity-released")
        val seasonal = ThemeId(ThemeKey.of("seasonal"), catalogId(tenant))
        withMediator {
            CreateTheme(seasonal, "Seasonal").execute()
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogVersion(tenant, letters).execute()
            DeleteTheme(seasonal).execute()
        }
        fun released(): UUID = jdbi.withHandle<UUID, Exception> { handle ->
            handle.createQuery("SELECT resource_id FROM release_entries WHERE tenant_key = :t AND version = '1.1.0' AND resource_type = 'theme' AND resource_key = 'seasonal'")
                .bind("t", tenant).mapTo(UUID::class.java).one()
        }

        assertThat(identityExists(tenant, "theme", "seasonal")).`as`("a release holds it").isTrue()

        val redesign = work(tenant, "Redesign")
        withMediator { CreateTheme(seasonal, "Seasonal", catalogVersion = at(tenant, redesign)).execute() }
        val recreated = jdbi.withHandle<UUID, Exception> { handle ->
            handle.createQuery("SELECT resource_id FROM themes WHERE tenant_key = :t AND version_id = :v AND id = 'seasonal'")
                .bind("t", tenant).bind("v", versionId(tenant, redesign)).mapTo(UUID::class.java).one()
        }
        assertThat(recreated).`as`("the same resource as the release's, so its history and findings stay with it").isEqualTo(released())
    }

    @Test
    fun `ready marks and dependency declarations belong to one draft`() {
        val tenant = released("drafts-ready")
        lateinit var redesign: DraftRef
        withMediator {
            CreateCatalog(tenant, CatalogKey.of("shared"), "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("house"), CatalogId(CatalogKey.of("shared"), TenantId(tenant))), "House").execute()
            mediator.releaseNext(CatalogId(CatalogKey.of("shared"), TenantId(tenant)))
        }
        redesign = work(tenant, "Redesign")
        withMediator {
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice two", catalogVersion = at(tenant, redesign)).execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice one", catalogVersion = next(tenant)).execute()
            MarkTemplateReady(invoice(tenant), catalogVersion = at(tenant, redesign)).execute()
        }

        assertThat(withMediator { GetVariantSummaries(invoice(tenant), catalogVersion = at(tenant, redesign)).query() }.map { it.ready }).containsOnly(true)
        assertThat(withMediator { GetVariantSummaries(invoice(tenant), catalogVersion = next(tenant)).query() }.map { it.ready }).containsOnly(false)
        assertThat(withMediator { GetCatalogResourceChanges(tenant, letters, catalogVersion = next(tenant)).query() }.notReady.map { it.slug }).containsExactly("invoice")
        assertThat(withMediator { GetCatalogResourceChanges(tenant, letters, catalogVersion = at(tenant, redesign)).query() }.notReady).isEmpty()

        val shared = withMediator { GetCatalog(tenant, CatalogKey.of("shared")).query()!! }
        withMediator {
            UpdateCatalogDependencies(tenant, letters, listOf(DeclaredCatalogDependency(shared.registry, shared.namespace, "shared", "1.0.0")), catalogVersion = at(tenant, redesign)).execute()
        }
        assertThat(withMediator { ListCatalogDependencies(tenant, letters, catalogVersion = at(tenant, redesign)).query() }.map { it.catalogKey }).containsExactly("shared")
        assertThat(withMediator { ListCatalogDependencies(tenant, letters, catalogVersion = next(tenant)).query() }).isEmpty()
    }

    @Test
    fun `a resource cannot be relocated while its catalog has long-term work`() {
        val tenant = released("drafts-relocate")
        withMediator { CreateCatalog(tenant, CatalogKey.of("shared"), "Shared").execute() }
        val move = listOf(ResourceAddress(CatalogResourceType.THEME, letters.value, "brand").movedTo(CatalogKey.of("shared")))
        assertThat(withMediator { PreviewCatalogResourceMove(tenant, move).query() }.blockers.map { it.code }).doesNotContain("several-drafts")

        work(tenant, "Redesign")

        assertThat(withMediator { PreviewCatalogResourceMove(tenant, move).query() }.blockers.map { it.code }).contains("several-drafts")
    }

    @Test
    fun `a nested target wins inside it and the caller's comes back after it`() {
        val tenant = released("drafts-nesting")
        val redesign = work(tenant, "Redesign")
        val nextId = versionId(tenant, DraftRef.Next)
        val work = versionId(tenant, redesign)
        fun current(): UUID = jdbi.withHandle<UUID, Exception> { it.createQuery("SELECT ${versionAt("t", "c")}").bind("t", tenant).bind("c", letters).mapTo(UUID::class.java).one() }

        val seen = TransactionTemplate(transactionManager).execute {
            listOf(current()) + targeting.within(work) { listOf(current()) + targeting.within(nextId) { listOf(current()) } + listOf(current()) } + listOf(current())
        }

        assertThat(seen).containsExactly(nextId, work, nextId, work, nextId)
    }
}
