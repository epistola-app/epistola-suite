// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.loadtest.batch

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.CatalogNotReleasedException
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.loadtest.commands.StartLoadTest
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * A load test renders what generation renders: the release the chosen environment serves, or the
 * catalog's latest release without one. It never names a template version.
 */
class LoadTestReleaseIT : IntegrationTestBase() {

    @Autowired
    private lateinit var executor: LoadTestExecutor

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `without an environment, a run renders the catalog's latest release`() {
        val (tenant, variant) = releasedTwice("lt-latest")

        val run = start(tenant, variant, environment = null)

        assertThat(rendered(run)).containsOnly("1.0.1")
    }

    @Test
    fun `with an environment, a run renders the release that environment serves, not the latest`() {
        val (tenant, variant) = releasedTwice("lt-env")
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
        withMediator {
            CreateEnvironment(production, "Production").execute()
            DeployRelease(production, variant.catalogKey, "1.0.0").execute()
        }

        val run = start(tenant, variant, environment = production.key)

        assertThat(rendered(run)).containsOnly("1.0.0")
    }

    @Test
    fun `a run with nothing to render is refused when it starts, with generation's own error`() {
        val tenant = createTenant("lt-refused").id
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenant))))
        withMediator { CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample() }

        assertThatThrownBy { start(tenant, variant, environment = null) }
            .isInstanceOf(CatalogNotReleasedException::class.java)

        withMediator {
            UpdateDraft(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(variant)
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), "Production").execute()
        }
        assertThatThrownBy { start(tenant, variant, environment = EnvironmentKey.of("production")) }
            .isInstanceOf(NoReleaseDeployedException::class.java)
    }

    /** The default catalog with releases 1.0.0 and 1.0.1 of a template `invoice`. */
    private fun releasedTwice(slug: String): Pair<TenantKey, VariantId> {
        val tenant = createTenant(slug).id
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenant))))
        withMediator {
            CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(variant)
            mediator.releaseNext(variant.templateId.catalogId)
        }
        return tenant to variant
    }

    private fun start(tenant: TenantKey, variant: VariantId, environment: EnvironmentKey?): LoadTestRun = withMediator {
        StartLoadTest(
            tenantId = tenant,
            catalogKey = variant.catalogKey,
            templateId = variant.templateId.key,
            variantId = variant.key,
            environmentId = environment,
            targetCount = 3,
            concurrencyLevel = 1,
            testData = objectMapper.createObjectNode() as ObjectNode,
        ).execute()
    }

    /** Submits the run's batch through real generation, and returns the releases its requests bound. */
    private fun rendered(run: LoadTestRun): List<String> {
        val batch: BatchKey = withMediator { executor.batchFor(run).execute() }
        return jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT release_version FROM document_generation_requests WHERE batch_id = :b")
                .bind("b", batch)
                .mapTo(String::class.java)
                .list()
        }.also { assertThat(it).hasSize(run.targetCount) }
    }
}
