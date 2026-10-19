// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
import app.epistola.suite.catalog.commands.UnregisterCatalog
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.revisions.ReleaseInUseException
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
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.commands.UndeployRelease
import app.epistola.suite.environments.queries.ListDeployableReleases
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * An environment serves any number of releases per catalog. Deploying adds one, undeploying removes
 * one; nothing an environment serves, or another release renders with, can be deleted or forgotten.
 */
class CatalogDeploymentTest : IntegrationTestBase() {

    @Test
    fun `deploying adds a release beside the others, and undeploying removes only that one`() {
        val (tenant, letters, production) = releasedTwice("dep-replace")

        withMediator { DeployRelease(production, letters.key, "1.0.0").execute() }
        assertThat(served(tenant, letters.key)).containsExactly(production.key to "1.0.0")

        withMediator { DeployRelease(production, letters.key, "1.0.1").execute() }
        assertThat(served(tenant, letters.key)).`as`("several releases per environment and catalog, highest first")
            .containsExactly(production.key to "1.0.1", production.key to "1.0.0")

        assertThat(withMediator { UndeployRelease(production, letters.key, "1.0.1").execute() }).isTrue()
        assertThat(served(tenant, letters.key)).containsExactly(production.key to "1.0.0")
        assertThat(withMediator { UndeployRelease(production, letters.key, "1.0.1").execute() }).`as`("no longer served").isFalse()
        assertThat(withMediator { UndeployRelease(production, letters.key, "1.0.0").execute() }).isTrue()
        assertThat(served(tenant, letters.key)).isEmpty()
    }

    @Test
    fun `undeploying the highest served release moves generation back to the next`() {
        val (tenant, letters, production) = releasedTwice("dep-fallback")
        withMediator {
            DeployRelease(production, letters.key, "1.0.0").execute()
            DeployRelease(production, letters.key, "1.0.1").execute()
        }
        assertThat(generatedRelease(tenant, letters, production)).isEqualTo("1.0.1")

        withMediator { UndeployRelease(production, letters.key, "1.0.1").execute() }

        assertThat(generatedRelease(tenant, letters, production)).isEqualTo("1.0.0")
    }

    @Test
    fun `a release that does not exist cannot be deployed`() {
        val (_, letters, production) = releasedTwice("dep-retained")

        assertThatThrownBy { withMediator { DeployRelease(production, letters.key, "9.9.9").execute() } }
            .isInstanceOf(ReleaseNotDeployableException::class.java)
            .hasMessageContaining("no such release")
    }

    @Test
    fun `deploying to an environment that does not exist is refused`() {
        val (tenant, letters, _) = releasedTwice("dep-noenv")

        assertThatThrownBy {
            withMediator { DeployRelease(EnvironmentId(EnvironmentKey.of("nowhere"), TenantId(tenant)), letters.key, "1.0.0").execute() }
        }.isInstanceOf(EnvironmentNotFoundException::class.java)
    }

    @Test
    fun `a deployed release cannot be deleted, and neither can its catalog`() {
        val (_, letters, production) = releasedTwice("dep-inuse")
        withMediator { DeployRelease(production, letters.key, "1.0.0").execute() }

        assertThatThrownBy { withMediator { DeleteCatalogRelease(letters.tenantKey, letters.key, "1.0.0").execute() } }
            .isInstanceOf(ReleaseInUseException::class.java)
            .hasMessageContaining("deployed to 'production'")
        assertThatThrownBy { withMediator { UnregisterCatalog(letters.tenantKey, letters.key, force = true).execute() } }
            .isInstanceOf(ReleaseInUseException::class.java)

        // The release nobody serves is free to go.
        assertThat(withMediator { DeleteCatalogRelease(letters.tenantKey, letters.key, "1.0.1").execute() }).isTrue()
    }

    @Test
    fun `a release another release is pinned to is kept until that release is deleted`() {
        val tenant = createTenant("dep-borrowed").id
        val shared = catalogIn(tenant, "shared")
        val letters = catalogIn(tenant, "letters")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
            mediator.releaseNext(shared)
            declareDependency(tenant, letters.key, on = shared.key)
            val templateId = TemplateId(TemplateKey.of("invoice"), letters)
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("brand"), themeCatalogKey = shared.key).execute()
            UpdateVariantContent(VariantId(VariantKey.INITIAL, templateId), TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(VariantId(VariantKey.INITIAL, templateId))
        }

        // letters@1.0.0 renders with shared@1.0.0, which no environment deploys.
        assertThatThrownBy { withMediator { DeleteCatalogRelease(tenant, shared.key, "1.0.0").execute() } }
            .isInstanceOf(ReleaseInUseException::class.java)
            .hasMessageContaining("pinned by letters@1.0.0")

        // Once letters@1.0.0 is gone, nothing renders from shared@1.0.0 either.
        withMediator {
            DeleteCatalogRelease(tenant, letters.key, "1.0.0").execute()
            DeleteCatalogRelease(tenant, shared.key, "1.0.0").execute()
        }
        assertThat(withMediator { ListCatalogReleases(tenant, shared.key).query() }.map { it.version }).doesNotContain("1.0.0")
    }

    @Test
    fun `every deploy and undeploy is logged, newest first, naming the release`() {
        val (_, letters, production) = releasedTwice("dep-history")

        withMediator {
            DeployRelease(production, letters.key, "1.0.0").execute()
            DeployRelease(production, letters.key, "1.0.1").execute()
            // Deploying what the environment already serves changes nothing, so it logs nothing.
            DeployRelease(production, letters.key, "1.0.1").execute()
            DeployRelease(production, letters.key, "1.0.0").execute()
            UndeployRelease(production, letters.key, "1.0.0").execute()
            DeployRelease(production, letters.key, "1.0.0").execute()
        }

        val history = withMediator { ListDeploymentHistory(production).query() }
        val catalog = withMediator { GetCatalog(production.tenantKey, letters.key).query()!! }
        assertThat(history).allSatisfy {
            assertThat(it.registry).isEqualTo(catalog.registry)
            assertThat(it.namespace).isEqualTo(catalog.namespace)
        }
        assertThat(history.map { Triple(it.action, it.version, it.previousVersion) }).containsExactly(
            Triple(DeploymentAction.DEPLOYED, "1.0.0", null),
            Triple(DeploymentAction.UNDEPLOYED, "1.0.0", null),
            Triple(DeploymentAction.DEPLOYED, "1.0.1", null),
            Triple(DeploymentAction.DEPLOYED, "1.0.0", null),
        )
        assertThat(history).allSatisfy { assertThat(it.catalogKey).isEqualTo(letters.key) }
        assertThat(history.first().changedByName).`as`("names who made the change").isNotBlank()
    }

    @Test
    fun `the history is limited in the database, keeping the newest`() {
        val (_, letters, production) = releasedTwice("dep-history-limit")
        withMediator {
            DeployRelease(production, letters.key, "1.0.0").execute()
            DeployRelease(production, letters.key, "1.0.1").execute()
            UndeployRelease(production, letters.key, "1.0.0").execute()
        }

        val newest = withMediator { ListDeploymentHistory(production, limit = 2).query() }

        assertThat(newest.map { it.action to it.version }).containsExactly(
            DeploymentAction.UNDEPLOYED to "1.0.0",
            DeploymentAction.DEPLOYED to "1.0.1",
        )
    }

    @Test
    fun `undeploying a release the environment does not serve logs nothing`() {
        val (_, letters, production) = releasedTwice("dep-history-noop")
        withMediator { DeployRelease(production, letters.key, "1.0.0").execute() }

        assertThat(withMediator { UndeployRelease(production, letters.key, "1.0.1").execute() }).isFalse()

        assertThat(withMediator { ListDeploymentHistory(production).query() }.map { it.action }).containsExactly(DeploymentAction.DEPLOYED)
    }

    @Test
    fun `undeploying from an environment that serves nothing logs nothing`() {
        val (_, letters, production) = releasedTwice("dep-history-none")

        withMediator { UndeployRelease(production, letters.key, "1.0.0").execute() }

        assertThat(withMediator { ListDeploymentHistory(production).query() }).isEmpty()
    }

    @Test
    fun `every release is deployable, newest first per catalog`() {
        val (tenant, letters, _) = releasedTwice("dep-deployable")

        val deployable = withMediator { ListDeployableReleases(tenant).query() }.filter { it.catalogKey == letters.key }

        assertThat(deployable.map { it.version }).containsExactly("1.0.1", "1.0.0")
        assertThat(deployable.map { it.catalogName }.distinct()).containsExactly("letters")
    }

    /** A catalog `letters` with releases 1.0.0 and 1.0.1, and an environment `production`. */
    private fun releasedTwice(slug: String): Triple<TenantKey, CatalogId, EnvironmentId> {
        val tenant = createTenant(slug).id
        val letters = catalogIn(tenant, "letters")
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
        withMediator {
            CreateEnvironment(production, "Production").execute()
            val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), letters))
            CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(variant)
            mediator.releaseNext(letters)
        }
        return Triple(tenant, letters, production)
    }

    private fun generatedRelease(tenant: TenantKey, letters: CatalogId, production: EnvironmentId): String? = withMediator {
        GenerateDocument(
            tenantId = tenant,
            catalogKey = letters.key,
            templateId = TemplateKey.of("invoice"),
            environmentId = production.key,
            data = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
            filename = null,
        ).execute().releaseVersion
    }

    private fun served(tenant: TenantKey, catalog: CatalogKey): List<Pair<EnvironmentKey, String>> = withMediator {
        ListDeployments(tenant, catalog).query().map { it.environmentKey to it.version }
    }

    private fun catalogIn(tenant: TenantKey, slug: String): CatalogId {
        val key = CatalogKey.of(slug)
        withMediator { CreateCatalog(tenantKey = tenant, id = key, name = slug).execute() }
        return CatalogId(key, TenantId(tenant))
    }
}
