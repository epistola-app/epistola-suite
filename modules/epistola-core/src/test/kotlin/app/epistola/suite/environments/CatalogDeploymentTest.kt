// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
import app.epistola.suite.catalog.commands.ForgetReleaseContent
import app.epistola.suite.catalog.commands.UnregisterCatalog
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
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.commands.UndeployRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
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
 * An environment serves one release per catalog. Deploying replaces it; nothing an environment
 * serves, or another release renders with, can be deleted or forgotten.
 */
class CatalogDeploymentTest : IntegrationTestBase() {

    @Test
    fun `deploying replaces what the environment served, and undeploying clears it`() {
        val (tenant, letters, production) = releasedTwice("dep-replace")

        withMediator { DeployRelease(production, letters.key, "1.0.0").execute() }
        assertThat(served(tenant, letters.key)).containsExactly(production.key to "1.0.0")

        withMediator { DeployRelease(production, letters.key, "1.0.1").execute() }
        assertThat(served(tenant, letters.key)).`as`("one release per environment and catalog").containsExactly(production.key to "1.0.1")

        assertThat(withMediator { UndeployRelease(production, letters.key).execute() }).isTrue()
        assertThat(served(tenant, letters.key)).isEmpty()
        assertThat(withMediator { UndeployRelease(production, letters.key).execute() }).`as`("nothing left to undeploy").isFalse()
    }

    @Test
    fun `a release that kept no content, or does not exist, cannot be deployed`() {
        val (_, letters, production) = releasedTwice("dep-retained")
        withMediator { ForgetReleaseContent(letters.tenantKey, letters.key, "1.0.0").execute() }

        assertThatThrownBy { withMediator { DeployRelease(production, letters.key, "1.0.0").execute() } }
            .isInstanceOf(ReleaseNotDeployableException::class.java)
            .hasMessageContaining("kept no content")
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
    fun `a deployed release cannot be deleted or forgotten, and its catalog cannot be deleted`() {
        val (_, letters, production) = releasedTwice("dep-inuse")
        withMediator { DeployRelease(production, letters.key, "1.0.0").execute() }

        assertThatThrownBy { withMediator { DeleteCatalogRelease(letters.tenantKey, letters.key, "1.0.0").execute() } }
            .isInstanceOf(ReleaseInUseException::class.java)
            .hasMessageContaining("deployed to 'production'")
        assertThatThrownBy { withMediator { ForgetReleaseContent(letters.tenantKey, letters.key, "1.0.0").execute() } }
            .isInstanceOf(ReleaseInUseException::class.java)
        assertThatThrownBy { withMediator { UnregisterCatalog(letters.tenantKey, letters.key, force = true).execute() } }
            .isInstanceOf(ReleaseInUseException::class.java)

        // The release nobody serves is free to go.
        assertThat(withMediator { DeleteCatalogRelease(letters.tenantKey, letters.key, "1.0.1").execute() }).isTrue()
    }

    @Test
    fun `a release another release renders with cannot be deleted or forgotten`() {
        val tenant = createTenant("dep-pinned").id
        val shared = catalogIn(tenant, "shared")
        val letters = catalogIn(tenant, "letters")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
            mediator.releaseNext(shared)
            val templateId = TemplateId(TemplateKey.of("invoice"), letters)
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("brand"), themeCatalogKey = shared.key).execute()
            UpdateDraft(VariantId(VariantKey.INITIAL, templateId), TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(VariantId(VariantKey.INITIAL, templateId))
        }

        assertThatThrownBy { withMediator { DeleteCatalogRelease(tenant, shared.key, "1.0.0").execute() } }
            .isInstanceOf(ReleaseInUseException::class.java)
            .hasMessageContaining("letters@1.0.0")
        assertThatThrownBy { withMediator { ForgetReleaseContent(tenant, shared.key, "1.0.0").execute() } }
            .isInstanceOf(ReleaseInUseException::class.java)
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
            UpdateDraft(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(variant)
            mediator.releaseNext(letters)
        }
        return Triple(tenant, letters, production)
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
