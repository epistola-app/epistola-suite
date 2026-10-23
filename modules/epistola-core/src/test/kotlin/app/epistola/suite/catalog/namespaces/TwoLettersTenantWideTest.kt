// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.suite.catalog.CatalogAddress
import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.queries.GetCatalogDependencyGraph
import app.epistola.suite.catalog.queries.GetCatalogOverview
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListReleaseContentHashes
import app.epistola.suite.catalog.releases.ApplyHouseTheme
import app.epistola.suite.catalog.releases.GetCatalogNames
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.queries.GetEnvironmentLatest
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.generation.release.CatalogSelection
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTenantCounter
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * What a tenant reads across all its catalogs at once keeps two catalogs called `letters` apart
 * (ADR 0034, phase 4): each is an entry of its own, by namespace and key, never one in place of the
 * other, and no read across the tenant is refused for the key they share.
 */
class TwoLettersTenantWideTest : IntegrationTestBase() {
    @Autowired
    private lateinit var usedCatalogs: UsedCatalogDeclarer

    private val letters = CatalogKey.of("letters")
    private val acme = CatalogAddress("acme", letters)
    private val bureau = CatalogAddress("bureau", letters)

    /** A tenant with `acme/letters` released as 1.0.0 and `bureau/letters` as 1.0.0 and 1.1.0, both deployed to production. */
    private fun twoLetters(name: String): TenantKey = withMediator {
        val tenant = CreateTenant(
            TenantKey.of("twoletters-${TestTenantCounter.next("twoletters")}"),
            name,
            listOf(NamespaceName("gemeente-x.nl", "acme"), NamespaceName("gemeente-x.nl", "bureau")),
        ).execute().id
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
        CreateEnvironment(production, "Production").execute()
        for ((namespace, releases) in listOf("acme" to 1, "bureau" to 2)) {
            CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
            CatalogNames.within(letters, namespace) {
                repeat(releases) { index ->
                    val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice-$index"), CatalogId(letters, TenantId(tenant))))
                    CreateDocumentTemplate(variant.templateId, "Invoice $index").execute()
                    DeployCatalogRelease(production, letters, mediator.publishAndRelease(variant)).execute()
                }
            }
        }
        tenant
    }

    @Test
    fun `the overview, editability, dependency graph and content hashes have an entry for each`() {
        val tenant = twoLetters("Two letters across the tenant")
        withMediator {
            val overview = GetCatalogOverview(tenant).query().filter { it.catalogKey == letters }
            assertThat(overview.map { it.namespace to it.latestRelease?.version }).containsExactlyInAnyOrder("acme" to "1.0.0", "bureau" to "1.1.0")
            assertThat(overview.map { it.segment }).containsExactlyInAnyOrder("acme/letters", "bureau/letters")

            assertThat(ListCatalogEditability(tenant).query().editableCatalogs).contains(acme, bureau)

            val graph = GetCatalogDependencyGraph(tenant).query()
            assertThat(graph.nodes.filter { it.catalogKey == "letters" }.map { it.namespace }).containsExactlyInAnyOrder("acme", "bureau")

            val hashes = ListReleaseContentHashes(tenant).query()
            assertThat(hashes.of(acme, "1.0.0")).isNotNull()
            assertThat(hashes.of(bureau, "1.0.0")).isNotNull()
            assertThat(hashes.byRelease.keys.filter { it.first.key == letters }).containsExactlyInAnyOrder(acme to "1.0.0", bureau to "1.0.0", bureau to "1.1.0")
            assertThat(hashes.of(acme, "1.1.0")).isNull()
        }
    }

    @Test
    fun `an environment serving both says for each what it serves`() {
        val tenant = twoLetters("Two letters in an environment")
        withMediator {
            val latest = GetEnvironmentLatest(tenant, EnvironmentKey.of("production")).query()
            assertThat(latest).containsEntry(acme, CatalogSelection.Release("1.0.0")).containsEntry(bureau, CatalogSelection.Release("1.1.0"))
            val served = ListDeployments(tenant, environmentKey = EnvironmentKey.of("production")).query()
            assertThat(served.filter { it.catalogKey == letters }.map { it.namespace to it.version })
                .containsExactlyInAnyOrder("acme" to "1.0.0", "bureau" to "1.0.0", "bureau" to "1.1.0")
        }
    }

    @Test
    fun `the house theme is applied to each`() {
        val tenant = twoLetters("Two letters house theme")
        withMediator {
            val brand = CatalogKey.of("brand")
            CreateCatalog(tenant, brand, "Brand", namespace = "acme").execute()
            CreateTheme(ThemeId(ThemeKey.of("house"), CatalogId(brand, TenantId(tenant))), "House").execute()
            SetTenantDefaultTheme(tenant, ThemeKey.of("house"), catalogKey = brand).execute()

            assertThat(ApplyHouseTheme(tenant).execute().applied).contains(acme, bureau)
        }
    }

    @Test
    fun `a use of a key both have declares the one the work names, and names none of them otherwise`() {
        val tenant = twoLetters("Two letters used")
        withMediator {
            val forms = CatalogKey.of("forms")
            CreateCatalog(tenant, forms, "Forms", namespace = "acme").execute()
            // Refused by the database; a message dispatching it says so in words (CatalogNames.ambiguity).
            assertThatThrownBy { usedCatalogs.declare(tenant, forms, letters) }
                .satisfies({ assertThat(CatalogNames.ambiguity(it)?.code).isEqualTo(ValidationCode.CATALOG_KEY_AMBIGUOUS) })
            val declared = CatalogNames.within(letters, "bureau") { usedCatalogs.declare(tenant, forms, letters) }
            assertThat(declared.map { it.namespace to it.catalogKey }).containsExactly("bureau" to "letters")

            assertThat(GetCatalogNames(tenant, listOf(letters)).query()).`as`("named by neither").isEmpty()
            assertThat(CatalogNames.within(letters, "acme") { GetCatalogNames(tenant, listOf(letters)).query() }[letters]?.namespace).isEqualTo("acme")
        }
    }

    @Test
    fun `the resource graph has the resources of each, named by namespace and key`() {
        val tenant = twoLetters("Two letters resource graph")
        withMediator {
            val invoices = GetTenantResourceGraph(tenant).query().nodes.filter { it.address.key == "invoice-0" }
            assertThat(invoices.map { it.address.catalogKey }).containsExactlyInAnyOrder("acme/letters", "bureau/letters")
            assertThat(invoices.map { it.address.id }.distinct()).hasSize(2)
        }
    }
}
