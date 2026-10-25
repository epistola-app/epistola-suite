// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.suite.attributes.codelists.commands.CodeListInUseException
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.DeleteCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.variants.validateAttributes
import app.epistola.suite.testing.AttributeStorageConversion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * A draft's variant attributes are stored as JSON (ADR 0033, step 3): `{ "resource":
 * <AttributeResource> }` named by a draft row. A code list binding is the wire form's alias and key,
 * indexed in `resource_references` and resolved through `resolve_catalog_alias`; it is no foreign key.
 */
class AttributeStorageTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var attributes: AttributeStore

    private fun one(sql: String): String = jdbi.withHandle<String, Exception> { it.createQuery(sql).mapTo(String::class.java).one() }

    @Test
    fun `an attribute is content naming its code list by alias and key, indexed, and the table is gone`() {
        val tenant = createTenant("Attribute storage").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val shared = testCatalog(tenant, CatalogKey.of("shared"))
        val language = AttributeId(AttributeKey.of("language"), letters)
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateCatalog(tenant, shared.key, "Shared").execute()
            CreateCodeList(CodeListId(CodeListKey.of("languages"), shared), "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands"))).execute()
            val created = CreateAttributeDefinition(language, "Language", codeListId = CodeListId(CodeListKey.of("languages"), shared)).execute()
            assertThat(created).`as`("what a create returns is what a read gives").isEqualTo(GetAttributeDefinition(language).query())
            assertThat(created.codeListId).isEqualTo(CodeListId(CodeListKey.of("languages"), shared))
        }

        val payload = objectMapper.readTree(one("SELECT payload::text FROM current_attributes WHERE tenant_key = '${tenant.value}' AND id = 'language' AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = '${tenant.value}' AND catalog_key = 'letters')"))
        assertThat(payload.path("resource").path("codeListBinding").path("catalogKey").asString()).isEqualTo("shared")
        assertThat(payload.path("resource").path("codeListBinding").path("key").asString()).isEqualTo("languages")
        assertThat(
            one(
                """
                SELECT x.alias || '/' || x.resource_type || '/' || x.resource_key FROM current_attributes a
                JOIN release_resources r ON r.tenant_key = a.tenant_key AND r.version_id = a.version_id AND r.resource_type = 'attribute' AND r.resource_key = a.id
                JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash
                WHERE a.tenant_key = '${tenant.value}' AND a.id = 'language' AND a.catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = '${tenant.value}' AND catalog_key = 'letters')
                """,
            ),
        ).isEqualTo("shared/codeList/languages")
        assertThat(one("SELECT CAST(to_regclass('variant_attribute_definitions') IS NULL AS TEXT)")).isEqualTo("true")

        val saved = withMediator { UpdateAttributeDefinition(language, "Language, renamed", codeListId = CodeListId(CodeListKey.of("languages"), shared)).execute() }
        assertThat(saved).`as`("what a save returns is what a read gives").isEqualTo(withMediator { GetAttributeDefinition(language).query() })
    }

    @Test
    fun `a bound code list is in use, through the references, and cannot be deleted`() {
        val tenant = createTenant("Attribute in use").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val list = CodeListId(CodeListKey.of("regions"), letters)
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateCodeList(list, "Regions", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("eu", "Europe"))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("region"), letters), "Region", codeListId = list).execute()
        }
        assertThatThrownBy { withMediator { DeleteCodeList(list).execute() } }.isInstanceOf(CodeListInUseException::class.java)
    }

    /**
     * An attribute bound to a code list of another catalog reads that list at the version its catalog
     * declares (ADR 0033, step 3): the release it pins, so an entry added to the list's draft since is
     * not yet a valid value, and the picker does not offer it.
     */
    @Test
    fun `a binding to another catalog reads the release that catalog's declaration pins`() {
        val tenant = createTenant("Attribute pinned binding").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val shared = testCatalog(tenant, CatalogKey.of("shared"))
        val list = CodeListId(CodeListKey.of("languages"), shared)
        val language = AttributeId(AttributeKey.of("language"), letters)
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateCatalog(tenant, shared.key, "Shared").execute()
            CreateCodeList(list, "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands"))).execute()
            val version = mediator.releaseNext(shared)
            CreateAttributeDefinition(language, "Language", codeListId = list).execute()
            // Letters uses shared's release, not its draft.
            val target = app.epistola.suite.catalog.queries.GetCatalog(shared).query()!!
            app.epistola.suite.catalog.commands.UpdateCatalogDependencies(
                letters,
                listOf(app.epistola.suite.catalog.commands.DeclaredCatalogDependency(target.registry, target.namespace, shared.key.value, version)),
            ).execute()
            UpdateCodeList(list, "Languages", entries = listOf(CodeListEntry("nl", "Nederlands"), CodeListEntry("fr", "Français"))).execute()
        }

        withMediator {
            validateAttributes(TenantId(tenant), mapOf("letters.language" to "nl"))
            assertThatThrownBy { validateAttributes(TenantId(tenant), mapOf("letters.language" to "fr")) }
                .`as`("not in the release the binding pins").isInstanceOf(ValidationException::class.java)
            assertThat(ListCodeListEntries(list, boundFrom = letters).query().map { it.code }).containsExactly("nl")
            assertThat(ListCodeListEntries(list).query().map { it.code }).`as`("the list's own draft").containsExactlyInAnyOrder("nl", "fr")
        }
    }

    @Test
    fun `every bundled attribute reads the same from the next version and from the release it holds`() {
        val tenant = createTenant("Attribute read parity").id
        val catalog = systemCatalog(tenant)
        val release = withMediator { ListCatalogReleases(catalog).query() }.first { it.status == CatalogReleaseStatus.RELEASED }
        val released = CatalogReleaseRef(catalog, DraftRef.Released(requireNotNull(release.version)))
        fun content(list: List<app.epistola.suite.attributes.model.VariantAttributeDefinition>) = list.map { listOf(it.id, it.catalogKey, it.displayName, it.allowedValues, it.codeListId) }.sortedBy { it.first().toString() }

        val drafted = withMediator { ListAttributeDefinitions(catalog.tenantId, catalog = catalog).query() }
        assertThat(drafted).isNotEmpty()
        assertThat(content(withMediator { ListAttributeDefinitions(catalog.tenantId, catalog = catalog, catalogVersion = released).query() })).isEqualTo(content(drafted))
    }

    @Test
    fun `the migration converts every bundled attribute as the suite stores it, both ways`() {
        val tenant = createTenant("Attribute conversion").id
        AttributeStorageConversion.verify(jdbi, objectMapper, attributes, systemCatalog(tenant))
    }

    @Test
    fun `the migration converts a cross-catalog binding as the suite stores it`() {
        val tenant = createTenant("Attribute conversion, bound").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("language"), letters), "Language", codeListId = CodeListId(CodeListKey.of("iso-639-1"), systemCatalog(tenant))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), letters), "Tone", allowedValues = listOf("formal", "casual")).execute()
        }
        AttributeStorageConversion.verify(jdbi, objectMapper, attributes, letters)
    }
}
