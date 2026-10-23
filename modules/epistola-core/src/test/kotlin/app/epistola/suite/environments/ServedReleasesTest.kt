// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.documents.ReleaseNotFoundException
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

/**
 * An environment serves several releases of a catalog, and a generation request picks among them
 * (contract 2.0): the highest it serves, or the highest satisfying the request's version selector.
 * Without an environment the candidates are every release that kept its content.
 */
class ServedReleasesTest : IntegrationTestBase() {

    @Test
    fun `an environment serves every release deployed to it, and generation renders the highest`() {
        val (_, letters, production) = released("served-highest", releases = 2)
        withMediator {
            DeployCatalogRelease(production, letters.key, "1.0.1").execute()
            // Deploying a lower release afterwards adds it; it does not take over.
            DeployCatalogRelease(production, letters.key, "1.0.0").execute()
        }

        assertThat(withMediator { ListDeployments(production.tenantKey, letters.key).query() }.map { it.version })
            .`as`("both served, highest first")
            .containsExactly("1.0.1", "1.0.0")
        assertThat(generate(letters, production.key, selector = null)).isEqualTo("1.0.1")
    }

    @Test
    fun `a version selector picks the highest served release satisfying it, even a lower one`() {
        val (_, letters, production) = released("served-selector", releases = 3)
        withMediator {
            DeployCatalogRelease(production, letters.key, "1.0.0").execute()
            DeployCatalogRelease(production, letters.key, "1.0.2").execute()
        }

        assertThat(generate(letters, production.key, selector = "1.0.0")).`as`("an exact version").isEqualTo("1.0.0")
        assertThat(generate(letters, production.key, selector = "<1.0.2")).`as`("a range, among what is served").isEqualTo("1.0.0")
        assertThat(generate(letters, production.key, selector = "^1.0.0")).isEqualTo("1.0.2")
    }

    @Test
    fun `a selector no served release satisfies is refused, though the catalog has a matching release`() {
        val (_, letters, production) = released("served-nomatch", releases = 2)
        withMediator { DeployCatalogRelease(production, letters.key, "1.0.0").execute() }

        assertThatThrownBy { generate(letters, production.key, selector = "1.0.1") }
            .isInstanceOf(NoReleaseDeployedException::class.java)
            .hasMessageContaining("matching 1.0.1")
        assertThatThrownBy { generate(letters, production.key, selector = "^2.0.0") }
            .isInstanceOf(NoReleaseDeployedException::class.java)
            .hasMessageContaining("matching ^2.0.0")
    }

    @Test
    fun `without an environment, a range picks the highest release that kept its content`() {
        val (_, letters, _) = released("served-noenv", releases = 3)

        assertThat(generate(letters, null, selector = "~1.0.0 <1.0.2")).isEqualTo("1.0.1")
        assertThat(generate(letters, null, selector = null)).isEqualTo("1.0.2")
        assertThatThrownBy { generate(letters, null, selector = "^2.0.0") }.isInstanceOf(ReleaseNotFoundException::class.java)
    }

    @Test
    fun `a selector that is not one is a validation failure on the version`() {
        val (_, letters, production) = released("served-invalid", releases = 1)
        withMediator { DeployCatalogRelease(production, letters.key, "1.0.0").execute() }

        assertThatThrownBy { generate(letters, production.key, selector = "[1.0,2.0)") }
            .isInstanceOf(ValidationException::class.java)
            .satisfies({ assertThat((it as ValidationException).field).isEqualTo("version") })
    }

    @Test
    fun `a batch item picks its release the same way`() {
        val (tenant, letters, production) = released("served-batch", releases = 2)
        withMediator {
            DeployCatalogRelease(production, letters.key, "1.0.0").execute()
            DeployCatalogRelease(production, letters.key, "1.0.1").execute()
        }

        val batch = withMediator {
            GenerateDocumentBatch(
                tenantId = tenant,
                items = listOf(
                    BatchGenerationItem(letters.key, INVOICE, environmentId = production.key, data = data(), filename = "a.pdf"),
                    BatchGenerationItem(letters.key, INVOICE, releaseVersion = "<1.0.1", environmentId = production.key, data = data(), filename = "b.pdf"),
                ),
            ).execute()
        }

        assertThat(batch.value).isNotNull()
        val rendered = withMediator { ListGenerationJobs(tenant).query() }
        assertThat(rendered.associate { it.filename to it.releaseVersion }).containsExactlyInAnyOrderEntriesOf(
            mapOf("a.pdf" to "1.0.1", "b.pdf" to "1.0.0"),
        )
    }

    private fun generate(catalog: CatalogId, environment: EnvironmentKey?, selector: String?): String? = withMediator {
        GenerateDocument(
            tenantId = catalog.tenantKey,
            catalogKey = catalog.key,
            templateId = INVOICE,
            variantId = VariantKey.INITIAL,
            releaseVersion = selector,
            environmentId = environment,
            data = data(),
            filename = null,
        ).execute().releaseVersion
    }

    private fun data() = JsonNodeFactory.instance.objectNode()

    /** A catalog `letters` with an invoice and releases 1.0.0, 1.0.1, …, and an environment `production`. */
    private fun released(slug: String, releases: Int): Triple<app.epistola.suite.common.ids.TenantKey, CatalogId, EnvironmentId> {
        val tenant = createTenant(slug).id
        val key = CatalogKey.of("letters")
        val letters = CatalogId(key, TenantId(tenant))
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
        withMediator {
            CreateCatalog(tenantKey = tenant, id = key, name = "Letters").execute()
            CreateEnvironment(production, "Production").execute()
            val variant = VariantId(VariantKey.INITIAL, TemplateId(INVOICE, letters))
            CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(variant)
            repeat(releases - 1) { mediator.releaseNext(letters) }
        }
        return Triple(tenant, letters, production)
    }

    private companion object {
        val INVOICE: TemplateKey = TemplateKey.of("invoice")
    }
}
