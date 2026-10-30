// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.suite.catalog.CatalogUpstreamCheckStore
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListReleaseContentHashes
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
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
import app.epistola.suite.exchange.ExchangeNamespaceBinder
import app.epistola.suite.generation.release.ReleaseRef
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.queries.GetStencilUsagePage
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestTenantCounter
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.GetThemeUsagePage
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/**
 * Everything that stores or follows a reference to a catalog keeps two catalogs called `letters`
 * apart (ADR 0034, section 9): by `catalog_id` where it is stored, by the catalog's address where
 * it is named, by alias through the referencing catalog's dependencies where content names it.
 * Each case read the catalog by its key alone before, which named neither of the two.
 */
class TwoLettersStoredReferencesTest : IntegrationTestBase() {

    @Autowired private lateinit var renderSource: ReleaseRenderSource

    @Autowired private lateinit var bindings: ExchangeNamespaceBinder

    @Autowired private lateinit var upstreamChecks: CatalogUpstreamCheckStore

    @Autowired private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")
    private val invoice = TemplateKey.of("invoice")

    private fun tenant(name: String): TenantKey = withMediator {
        CreateTenant(
            TenantKey.of("storedrefs-${TestTenantCounter.next("storedrefs")}"),
            name,
            listOf(NamespaceName("gemeente-x.nl", "acme"), NamespaceName("gemeente-x.nl", "bureau")),
        ).execute().id
    }

    private fun letters(tenant: TenantKey, namespace: String) = CatalogId(namespace, letters, TenantId(tenant))

    /** `letters` in both namespaces of [tenant], each with an invoice titled after it, released as 1.0.0. */
    private fun twoLetters(tenant: TenantKey) = withMediator {
        for (namespace in listOf("acme", "bureau")) {
            CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
            val variant = VariantId(VariantKey.INITIAL, TemplateId(invoice, letters(tenant, namespace)))
            CreateDocumentTemplate(variant.templateId, "Invoice of $namespace").execute().withRequiredDataExample()
            UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Invoice of $namespace")).execute()
            mediator.publishAndRelease(variant)
        }
    }

    @Test
    fun `a template using one of the two renders and borrows that one`() {
        val tenant = tenant("Two letters pins")
        val forms = CatalogId("acme", CatalogKey.of("forms"), TenantId(tenant))
        withMediator {
            for ((namespace, colour) in listOf("acme" to "#0000ff", "bureau" to "#ff0000")) {
                CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
                CreateTheme(ThemeId(ThemeKey.of("brand"), letters(tenant, namespace)), "Brand of $namespace", documentStyles = mapOf("color" to colour)).execute()
                MarkCatalogReady(letters(tenant, namespace)).execute()
                ReleaseCatalogDraft(letters(tenant, namespace), "1.0.0").execute()
            }
            CreateCatalog(tenant, forms.key, "Forms", namespace = "acme").execute()
            val variant = VariantId(VariantKey.INITIAL, TemplateId(invoice, forms))
            CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            // The theme of bureau's letters: declared on use, under its key.
            UpdateDocumentTemplate(variant.templateId, themeId = ThemeKey.of("brand"), themeCatalog = letters(tenant, "bureau")).execute()

            val borrowed = GetCatalogBorrowing(forms).query().catalogs.single()
            assertThat(borrowed.address).isEqualTo("bureau/letters")
            assertThat(borrowed.uses.map { it.type to it.key }).contains("theme" to "brand")

            mediator.publishAndRelease(variant)
        }

        val inputs = withMediator { renderSource.resolve(GetTenant(tenant).query()!!, ReleaseRef(forms, "1.0.0"), invoice.value, VariantKey.INITIAL.value) }
        assertThat(inputs.resolvedTheme.documentStyles).containsEntry("color", "#ff0000")
    }

    @Test
    fun `a stencil's usage page lists the templates of its own catalog only`() {
        val tenant = tenant("Two letters stencil usage")
        val letterhead = StencilKey.of("letterhead")
        withMediator {
            for (namespace in listOf("acme", "bureau")) {
                CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
                CreateStencil(StencilId(letterhead, letters(tenant, namespace)), "Letterhead").execute()
                val variant = VariantId(VariantKey.INITIAL, TemplateId(invoice, letters(tenant, namespace)))
                CreateDocumentTemplate(variant.templateId, "Invoice of $namespace").execute()
                UpdateVariantContent(variant, embedding("letterhead")).execute()
            }

            val usage = GetStencilUsagePage(StencilId(letterhead, letters(tenant, "acme"))).query()
            assertThat(usage.items.map { it.catalogAddress to it.templateName }).containsExactly("acme/letters" to "Invoice of acme")
        }
    }

    @Test
    fun `a theme's usage page lists the templates that use that theme only`() {
        val tenant = tenant("Two letters theme usage")
        val brand = ThemeKey.of("brand")
        withMediator {
            for (namespace in listOf("acme", "bureau")) {
                CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
                CreateTheme(ThemeId(brand, letters(tenant, namespace)), "Brand of $namespace").execute()
                val template = TemplateId(invoice, letters(tenant, namespace))
                CreateDocumentTemplate(template, "Invoice of $namespace").execute()
                UpdateDocumentTemplate(template, themeId = brand, themeCatalog = letters(tenant, namespace)).execute()
            }

            val usage = GetThemeUsagePage(ThemeId(brand, letters(tenant, "acme"))).query()
            assertThat(usage.items.map { it.templateCatalogAddress to it.templateName }).containsExactly("acme/letters" to "Invoice of acme")
        }
    }

    @Test
    fun `publishing either fixes the namespace of that one only`() {
        val tenant = tenant("Two letters bindings")
        withMediator {
            CreateCatalog(tenant, letters, "Acme letters", namespace = "acme").execute()
            CreateCatalog(tenant, letters, "Bureau letters", namespace = "bureau").execute()
        }

        jdbi.useHandle<Exception> { handle ->
            bindings.markPublished(handle, letters(tenant, "acme"), "acme")
            assertThat(bindings.isPublished(handle, letters(tenant, "acme"))).isTrue()
            assertThat(bindings.isPublished(handle, letters(tenant, "bureau"))).isFalse()
        }
    }

    @Test
    fun `the upstream check claims and records each of the two`() {
        val tenant = tenant("Two letters upstream")
        withMediator {
            CreateCatalog(tenant, letters, "Acme letters", namespace = "acme").execute()
            CreateCatalog(tenant, letters, "Bureau letters", namespace = "bureau").execute()
        }
        // Raw SQL: a source on a catalog made here, which no command sets; the check only reads it.
        jdbi.useHandle<Exception> { it.createUpdate("UPDATE catalogs SET source_url = 'exchange:' || namespace || '/letters' WHERE tenant_key = :t AND catalog_key = 'letters'").bind("t", tenant).execute() }
        upstreamChecks.recordInstalled(letters(tenant, "acme"), "1.0.0", null)
        upstreamChecks.recordInstalled(letters(tenant, "bureau"), "2.0.0", null)
        // Raw SQL: due now, which only the database clock moves.
        jdbi.useHandle<Exception> { it.createUpdate("UPDATE catalog_upstream_checks SET next_check_at = NOW() - INTERVAL '1 minute' WHERE tenant_key = :t").bind("t", tenant).execute() }

        val claimed = upstreamChecks.claimDue(100, Duration.ofMinutes(5)).filter { it.tenantKey == tenant }
        assertThat(claimed.map { it.address }).containsExactlyInAnyOrder("acme/letters", "bureau/letters")
        assertThat(upstreamChecks.stateFor(letters(tenant, "acme"))!!.availableVersion).isEqualTo("1.0.0")
        assertThat(upstreamChecks.stateFor(letters(tenant, "bureau"))!!.availableVersion).isEqualTo("2.0.0")
    }

    /** A document embedding stencil [stencilKey] of its own catalog. */
    private fun embedding(stencilKey: String) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "instance" to Node(id = "instance", type = "stencil", slots = listOf("instance-children"), props = mapOf("stencilKey" to stencilKey)),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("instance")),
            "instance-children" to Slot(id = "instance-children", nodeId = "instance", name = "children", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
