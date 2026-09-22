// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.batch.DocumentGenerationExecutor
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.fonts.FontByteCache
import app.epistola.suite.fonts.FontSnapshotVerifier
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.validation.JsonSchemaValidator
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Generating a moved template, through the production [DocumentGenerationExecutor] -- built here
 * from its dependencies, because integration tests wire a fake executor in its place that renders
 * nothing and never looks the template up.
 */
class GenerationAfterRelocationTest : RelocationTestSupport() {

    @Autowired private lateinit var generationService: GenerationService

    @Autowired private lateinit var schemaValidator: JsonSchemaValidator

    @Autowired private lateinit var contentStore: DocumentContentStore

    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Autowired private lateinit var fontSnapshotVerifier: FontSnapshotVerifier

    @Autowired private lateinit var fontByteCache: FontByteCache

    @Autowired private lateinit var localeResolver: TenantLocaleResolver

    private val realExecutor by lazy {
        DocumentGenerationExecutor(
            jdbi, generationService, mediator, objectMapper, schemaValidator, contentStore,
            meterRegistry, fontSnapshotVerifier, fontByteCache, localeResolver,
            retentionDays = 7, maxDocumentSizeMb = 50,
        )
    }

    @Test
    fun `a moved template generates at its new address, by version and through an environment`() {
        val tenant = tenantWith("Generate moved template")
        val staging = EnvironmentId(EnvironmentKey.of("staging"), TenantId(tenant))
        val version = publishTemplate(tenant, letters, textModel())
        withMediator {
            CreateEnvironment(staging, "Staging").execute()
            PublishToEnvironment(VersionId(version, templateVariant(tenant, letters)), staging).execute()
        }

        move(tenant, address(CatalogResourceType.TEMPLATE, letters, "invoice").movedTo(shared))

        assertGenerates(tenant, withMediator { request(tenant, shared, version, environment = null) })
        assertGenerates(tenant, withMediator { request(tenant, shared, version = null, environment = staging.key) })
    }

    /**
     * A request stores the address it was made against. One accepted before the template moved and
     * rendered after finds nothing there and fails: a crude move does not follow queued work.
     */
    @Test
    fun `a request queued before its template moves fails`() {
        val tenant = tenantWith("Generate queued across move")
        val version = publishTemplate(tenant, letters, textModel())
        val queued = withMediator { request(tenant, letters, version, environment = null) }

        move(tenant, address(CatalogResourceType.TEMPLATE, letters, "invoice").movedTo(shared))

        withMediator { realExecutor.execute(queued) }
        val job = withMediator { GetGenerationJob(tenant, queued.id).query()!! }
        assertThat(job.request.status).isEqualTo(RequestStatus.FAILED)
    }

    private fun request(tenant: TenantKey, catalog: CatalogKey, version: VersionKey?, environment: EnvironmentKey?) = GenerateDocument(
        tenantId = tenant,
        catalogKey = catalog,
        templateId = TemplateKey.of("invoice"),
        variantId = VariantKey.INITIAL,
        versionId = version,
        environmentId = environment,
        data = objectMapper.createObjectNode(),
        filename = "invoice.pdf",
    ).execute()

    /** Runs the stored [request] through the production executor, as a render worker would. */
    private fun assertGenerates(tenant: TenantKey, request: DocumentGenerationRequest) {
        withMediator { realExecutor.execute(request) }
        val job = withMediator { GetGenerationJob(tenant, request.id).query()!! }
        assertThat(job.request.status).describedAs("request error: %s", job.request.errorMessage).isEqualTo(RequestStatus.COMPLETED)
    }
}
