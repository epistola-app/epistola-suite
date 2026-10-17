// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Several drafts of one catalog at once (contract ADR 0002): each draft is an independent copy with a
 * fixed base, a message that names a draft ([CatalogVersionScoped]) reads and writes that draft, and
 * releasing, discarding and renumbering a draft change no other.
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

    private fun at(tenant: TenantKey, version: String) = CatalogVersionRef(tenant, letters, version)

    /** `letters` with a brand theme and an invoice on it, released as 1.0.0; its draft is 1.0.1. */
    private fun released(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = letters).execute()
        mediator.releaseNext(catalogId(tenant))
        tenant
    }

    private fun themeName(tenant: TenantKey, version: String?) = withMediator { GetTheme(brand(tenant), catalogVersion = version?.let { at(tenant, it) }).query()?.name }

    private fun versions(tenant: TenantKey) = withMediator { ListCatalogVersions(tenant, letters).query() }.map { it.version to it.status }

    private fun versionId(tenant: TenantKey, version: String): UUID = jdbi.withHandle<UUID, Exception> { handle ->
        handle.createQuery(
            "SELECT v.version_id FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id " +
                "WHERE c.tenant_key = :t AND c.catalog_key = :c AND v.version = :v",
        ).bind("t", tenant).bind("c", letters).bind("v", version).mapTo(UUID::class.java).one()
    }

    /** Every resource part's content digest a version holds: equal maps are equal content. */
    private fun digests(tenant: TenantKey, version: String): Map<Pair<UUID, String>, String> = jdbi.withHandle<Map<Pair<UUID, String>, String>, Exception> { handle ->
        handle.createQuery("SELECT resource_id, part, digest FROM resource_content_digests WHERE tenant_key = :t AND version_id = :v")
            .bind("t", tenant).bind("v", versionId(tenant, version))
            .map { rs, _ -> (rs.getObject("resource_id", UUID::class.java) to rs.getString("part")) to rs.getString("digest") }
            .list().toMap()
    }

    private fun identityExists(tenant: TenantKey, type: String, key: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            "SELECT EXISTS (SELECT 1 FROM catalog_resources r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id " +
                "WHERE r.tenant_key = :t AND c.catalog_key = :c AND r.resource_type = :type AND r.resource_key = :key)",
        ).bind("t", tenant).bind("c", letters).bind("type", type).bind("key", key).mapTo(Boolean::class.java).one()
    }

    private fun rowsIn(tenant: TenantKey, version: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            """
            SELECT (SELECT count(*) FROM themes WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM document_templates WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM template_variants WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM code_lists WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM code_list_entries WHERE tenant_key = :t AND version_id = :v)
                 + (SELECT count(*) FROM variant_attribute_definitions WHERE tenant_key = :t AND version_id = :v)
            """,
        ).bind("t", tenant).bind("v", versionId(tenant, version)).mapTo(Int::class.java).one()
    }

    @Test
    fun `two drafts are edited independently, each read through the version it names`() {
        val tenant = released("drafts-independent")
        withMediator { CreateCatalogVersion(tenant, letters, "2.0.0").execute() }

        withMediator { UpdateTheme(brand(tenant), name = "Brand two", catalogVersion = at(tenant, "2.0.0")).execute() }
        withMediator { UpdateTheme(brand(tenant), name = "Brand one", catalogVersion = at(tenant, "1.0.1")).execute() }

        assertThat(themeName(tenant, "2.0.0")).isEqualTo("Brand two")
        assertThat(themeName(tenant, "1.0.1")).isEqualTo("Brand one")
        // A caller that names none means the current version, the highest draft.
        assertThat(themeName(tenant, null)).isEqualTo("Brand two")
        // One resource, one identity, in both.
        assertThat(digests(tenant, "1.0.1").keys.map { it.first }.toSet()).isEqualTo(digests(tenant, "2.0.0").keys.map { it.first }.toSet())
        // A reference resolves in the version being read.
        val template = withMediator { GetDocumentTemplate(invoice(tenant), catalogVersion = at(tenant, "1.0.1")).query()!! }
        assertThat(template.themeKey?.value).isEqualTo("brand")
    }

    @Test
    fun `a draft created from another draft is a copy of it, with its base and ready marks`() {
        val tenant = released("drafts-from-draft")
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand, edited").execute()
            val colours = CodeListId(CodeListKey.of("colours"), catalogId(tenant))
            CreateCodeList(colours, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"), CodeListEntry("blue", "Blue", 1))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("colour"), catalogId(tenant)), "Colour", codeListId = colours).execute()
            MarkTemplateReady(invoice(tenant)).execute()
        }

        val created = withMediator { CreateCatalogVersion(tenant, letters, "3.0.0", from = "1.0.1").execute() }

        assertThat(created.base).isEqualTo("1.0.0")
        assertThat(created.createdFrom).isEqualTo("1.0.1")
        assertThat(digests(tenant, "3.0.0")).isEqualTo(digests(tenant, "1.0.1")).isNotEmpty()
        assertThat(rowsIn(tenant, "3.0.0")).isEqualTo(rowsIn(tenant, "1.0.1"))
        assertThat(withMediator { GetVariantSummaries(invoice(tenant), catalogVersion = at(tenant, "3.0.0")).query() }.map { it.ready }).containsOnly(true)
    }

    @Test
    fun `a draft created from a release, or from its base by default, holds what the release holds`() {
        val tenant = released("drafts-from-release")
        val releasedDigests = digests(tenant, "1.0.1")
        withMediator { UpdateTheme(brand(tenant), name = "Brand, edited later").execute() }

        val fromRelease = withMediator { CreateCatalogVersion(tenant, letters, "1.0.5", from = "1.0.0").execute() }
        val fromBase = withMediator { CreateCatalogVersion(tenant, letters, "2.0.0").execute() }

        assertThat(fromRelease.base).isEqualTo("1.0.0")
        assertThat(fromRelease.createdFrom).isNull()
        assertThat(fromBase.base).isEqualTo("1.0.0")
        assertThat(themeName(tenant, "1.0.5")).isEqualTo("Brand")
        assertThat(themeName(tenant, "2.0.0")).isEqualTo("Brand")
        // Against its base, a copy of the base has changed nothing.
        val changes = withMediator { GetCatalogResourceChanges(tenant, letters, catalogVersion = at(tenant, "2.0.0")).query() }
        assertThat(changes.resources.map { it.state }).containsOnly(CatalogResourceState.RELEASED)
        // The same resources, under the same identities. (Stored rows may differ from the release in
        // what its canonical form leaves out, such as a data example's generated id, so the canonical
        // comparison above is the content check.)
        assertThat(digests(tenant, "2.0.0").keys).isEqualTo(releasedDigests.keys)
    }

    @Test
    fun `a number in use, an unknown source and a subscribed catalog are refused`() {
        val tenant = released("drafts-refused")
        assertThatThrownBy { withMediator { CreateCatalogVersion(tenant, letters, "1.0.0").execute() } }.isInstanceOf(CatalogVersionInUseException::class.java)
        assertThatThrownBy { withMediator { CreateCatalogVersion(tenant, letters, "1.0.1").execute() } }.isInstanceOf(CatalogVersionInUseException::class.java)
        assertThatThrownBy { withMediator { CreateCatalogVersion(tenant, letters, "2.0.0", from = "1.5.0").execute() } }.isInstanceOf(CatalogVersionNotFoundException::class.java)
        // A message naming a version runs on it, so one that does not exist, or is released, is refused.
        assertThatThrownBy { themeName(tenant, "9.9.9") }.isInstanceOf(CatalogVersionNotFoundException::class.java)
        assertThatThrownBy { withMediator { UpdateTheme(brand(tenant), name = "x", catalogVersion = at(tenant, "1.0.0")).execute() } }
            .isInstanceOf(CatalogVersionReleasedException::class.java)
    }

    @Test
    fun `deleting a draft deletes only its content, and a resource keeps its identity while another draft holds it`() {
        val tenant = released("drafts-delete")
        withMediator { CreateCatalogVersion(tenant, letters, "2.0.0").execute() }
        val kept = rowsIn(tenant, "1.0.1")

        withMediator { DeleteTheme(brand(tenant), catalogVersion = at(tenant, "2.0.0")).execute() }
        assertThat(themeName(tenant, "2.0.0")).isNull()
        assertThat(themeName(tenant, "1.0.1")).isEqualTo("Brand")
        assertThat(identityExists(tenant, "theme", "brand")).isTrue()

        withMediator { DeleteCatalogVersion(tenant, letters, "2.0.0").execute() }
        assertThat(versions(tenant).map { it.first }).containsExactly("1.0.1", "1.0.0")
        assertThat(rowsIn(tenant, "1.0.1")).isEqualTo(kept)
        assertThat(identityExists(tenant, "template", "invoice")).isTrue()

        assertThatThrownBy { withMediator { DeleteCatalogVersion(tenant, letters, "1.0.0").execute() } }.isInstanceOf(CatalogVersionReleasedException::class.java)
        assertThatThrownBy { withMediator { DeleteCatalogVersion(tenant, letters, "7.0.0").execute() } }.isInstanceOf(CatalogVersionNotFoundException::class.java)

        // With no version left holding it, the identity goes.
        withMediator { DeleteTheme(brand(tenant), catalogVersion = at(tenant, "1.0.1")).execute() }
        assertThat(identityExists(tenant, "theme", "brand")).isFalse()
    }

    @Test
    fun `releasing the lower of two drafts discards it rather than advancing past the other, and the higher is behind it`() {
        val tenant = released("drafts-release-lower")
        withMediator { CreateCatalogVersion(tenant, letters, "2.0.0").execute() }
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand, fixed", catalogVersion = at(tenant, "1.0.1")).execute()
            MarkCatalogReady(tenant, letters, catalogVersion = at(tenant, "1.0.1")).execute()
        }

        val result = withMediator { ReleaseCatalogVersion(tenant, letters, "1.0.1").execute() }

        assertThat(result.openedDraft).isNull()
        assertThat(result.draftsBehind).containsExactly("2.0.0")
        assertThat(versions(tenant)).containsExactly(
            "2.0.0" to CatalogVersionStatus.DRAFT,
            "1.0.1" to CatalogVersionStatus.RELEASED,
            "1.0.0" to CatalogVersionStatus.RELEASED,
        )
        assertThat(withMediator { GetReleasedResource(tenant, letters, "1.0.1", "theme", "brand").query() }?.name).isEqualTo("Brand, fixed")
        assertThat(themeName(tenant, "2.0.0")).isEqualTo("Brand")
        // The catalog's released version stays its highest release.
        assertThat(withMediator { GetCatalog(tenant, letters).query()!! }.releasedVersion).isEqualTo("1.0.1")

        val draft = withMediator { GetCatalogVersion(tenant, letters, "2.0.0").query()!! }
        assertThat(draft.behind.map { it.version }).containsExactly("1.0.1")
        assertThat(draft.behind.single().changes).containsExactly(ReleasedChange("theme", "brand", "modified"))

        withMediator { MarkCatalogReady(tenant, letters, catalogVersion = at(tenant, "2.0.0")).execute() }
        assertThatThrownBy { withMediator { ReleaseCatalogVersion(tenant, letters, "2.0.0").execute() } }
            .isInstanceOf(CatalogVersionBehindException::class.java)
            .hasMessageContaining("1.0.1")
        val higher = withMediator { ReleaseCatalogVersion(tenant, letters, "2.0.0", acknowledgedBehind = setOf("1.0.1")).execute() }
        assertThat(higher.openedDraft).isEqualTo("2.0.1")
        assertThat(versions(tenant).first()).isEqualTo("2.0.1" to CatalogVersionStatus.DRAFT)
    }

    @Test
    fun `renumbering warns about releases it falls behind, releases it contains and drafts it passes`() {
        val tenant = released("drafts-renumber")
        withMediator { CreateCatalogVersion(tenant, letters, "1.1.0").execute() }
        // 1.0.1 -> 1.2.0 moves past draft 1.1.0.
        val passing = withMediator { PreviewCatalogVersionRenumber(tenant, letters, "1.0.1", "1.2.0").query() }
        assertThat(passing.map { it.kind to it.versions }).containsExactly("passes-draft" to listOf("1.1.0"))
        assertThatThrownBy { withMediator { RenumberCatalogVersion(tenant, letters, "1.0.1", "1.2.0").execute() } }
            .isInstanceOf(RenumberWarningsNotAcknowledgedException::class.java)
        withMediator { RenumberCatalogVersion(tenant, letters, "1.0.1", "1.2.0", acknowledgeWarnings = true).execute() }
        withMediator {
            MarkCatalogReady(tenant, letters, catalogVersion = at(tenant, "1.2.0")).execute()
            ReleaseCatalogVersion(tenant, letters, "1.2.0").execute()
        }
        assertThat(versions(tenant).map { it.first }).containsExactly("1.2.1", "1.2.0", "1.1.0", "1.0.0")

        // Draft 1.1.0, on 1.0.0, moved above release 1.2.0 would not contain it.
        val up = withMediator { PreviewCatalogVersionRenumber(tenant, letters, "1.1.0", "1.3.0").query() }
        assertThat(up.map { it.kind to it.versions }).containsExactly("falls-behind" to listOf("1.2.0"), "passes-draft" to listOf("1.2.1"))
        // Draft 1.2.1, on 1.2.0, moved below it would release its changes under a lower number.
        val down = withMediator { PreviewCatalogVersionRenumber(tenant, letters, "1.2.1", "1.1.5").query() }
        assertThat(down.map { it.kind to it.versions }).containsExactly("contains-later-releases" to listOf("1.2.0"))
        // Within its gap, nothing.
        assertThat(withMediator { PreviewCatalogVersionRenumber(tenant, letters, "1.1.0", "1.1.3").query() }).isEmpty()
    }

    @Test
    fun `ready marks and dependency declarations belong to one draft`() {
        val tenant = released("drafts-ready")
        withMediator {
            CreateCatalog(tenant, CatalogKey.of("shared"), "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("house"), CatalogId(CatalogKey.of("shared"), TenantId(tenant))), "House").execute()
            mediator.releaseNext(CatalogId(CatalogKey.of("shared"), TenantId(tenant)))
            CreateCatalogVersion(tenant, letters, "2.0.0").execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice two", catalogVersion = at(tenant, "2.0.0")).execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice one", catalogVersion = at(tenant, "1.0.1")).execute()
            MarkTemplateReady(invoice(tenant), catalogVersion = at(tenant, "2.0.0")).execute()
        }

        assertThat(withMediator { GetVariantSummaries(invoice(tenant), catalogVersion = at(tenant, "2.0.0")).query() }.map { it.ready }).containsOnly(true)
        assertThat(withMediator { GetVariantSummaries(invoice(tenant), catalogVersion = at(tenant, "1.0.1")).query() }.map { it.ready }).containsOnly(false)
        assertThat(withMediator { GetCatalogResourceChanges(tenant, letters, catalogVersion = at(tenant, "1.0.1")).query() }.notReady.map { it.slug }).containsExactly("invoice")
        assertThat(withMediator { GetCatalogResourceChanges(tenant, letters, catalogVersion = at(tenant, "2.0.0")).query() }.notReady).isEmpty()

        val shared = withMediator { GetCatalog(tenant, CatalogKey.of("shared")).query()!! }
        withMediator {
            UpdateCatalogDependencies(tenant, letters, listOf(DeclaredCatalogDependency(shared.registry, shared.namespace, "shared", "^1.0.0")), catalogVersion = at(tenant, "2.0.0")).execute()
        }
        assertThat(withMediator { ListCatalogDependencies(tenant, letters, catalogVersion = at(tenant, "2.0.0")).query() }.map { it.catalogKey }).containsExactly("shared")
        assertThat(withMediator { ListCatalogDependencies(tenant, letters, catalogVersion = at(tenant, "1.0.1")).query() }).isEmpty()
    }

    @Test
    fun `a resource cannot be relocated while its catalog has several drafts`() {
        val tenant = released("drafts-relocate")
        withMediator { CreateCatalog(tenant, CatalogKey.of("shared"), "Shared").execute() }
        val move = listOf(ResourceAddress(CatalogResourceType.THEME, letters.value, "brand").movedTo(CatalogKey.of("shared")))
        assertThat(withMediator { PreviewCatalogResourceMove(tenant, move).query() }.blockers.map { it.code }).doesNotContain("several-drafts")

        withMediator { CreateCatalogVersion(tenant, letters, "2.0.0").execute() }

        assertThat(withMediator { PreviewCatalogResourceMove(tenant, move).query() }.blockers.map { it.code }).contains("several-drafts")
    }

    @Test
    fun `a nested target wins inside it and the caller's comes back after it`() {
        val tenant = released("drafts-nesting")
        withMediator { CreateCatalogVersion(tenant, letters, "2.0.0").execute() }
        val one = versionId(tenant, "1.0.1")
        val two = versionId(tenant, "2.0.0")
        fun current(): UUID = jdbi.withHandle<UUID, Exception> { it.createQuery("SELECT ${versionAt("t", "c")}").bind("t", tenant).bind("c", letters).mapTo(UUID::class.java).one() }

        val seen = TransactionTemplate(transactionManager).execute {
            listOf(current()) + targeting.within(one) { listOf(current()) + targeting.within(two) { listOf(current()) } + listOf(current()) } + listOf(current())
        }

        assertThat(seen).containsExactly(two, one, two, one, two)
    }
}
