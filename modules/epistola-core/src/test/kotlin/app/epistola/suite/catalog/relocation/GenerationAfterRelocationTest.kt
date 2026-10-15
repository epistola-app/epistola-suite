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
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.testing.deployLatestRelease
import app.epistola.suite.testing.releaseNext
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

    @Autowired
    private lateinit var releaseRenderSource: app.epistola.suite.generation.release.ReleaseRenderSource

    private val realExecutor by lazy {
        DocumentGenerationExecutor(
            jdbi, generationService, mediator, objectMapper, schemaValidator, contentStore,
            meterRegistry, fontSnapshotVerifier, fontByteCache, localeResolver, releaseRenderSource,
            retentionDays = 7, maxDocumentSizeMb = 50,
        )
    }

    @Test
    fun `a moved template generates from its new catalog's release and through an environment`() {
        val tenant = tenantWith("Generate moved template")
        val staging = EnvironmentId(EnvironmentKey.of("staging"), TenantId(tenant))
        publishTemplate(tenant, letters, textModel())
        withMediator { CreateEnvironment(staging, "Staging").execute() }

        move(tenant, address(CatalogResourceType.TEMPLATE, letters, "invoice").movedTo(shared))
        // Generation renders a release, so the template's new catalog is released after the move,
        // and staging serves that release.
        withMediator {
            mediator.releaseNext(catalogId(tenant, shared))
            mediator.deployLatestRelease(staging, catalogId(tenant, shared))
        }

        assertGenerates(tenant, withMediator { request(tenant, shared, environment = null) })
        assertGenerates(tenant, withMediator { request(tenant, shared, environment = staging.key) })
    }

    /**
     * A request is bound to the release that was latest when it was accepted. A template moved
     * afterwards is gone from the working copy, but not from that release, so the queued request
     * still renders: a move changes the working copy, never a release.
     */
    @Test
    fun `a request queued before its template moves still renders the release it was accepted against`() {
        val tenant = tenantWith("Generate queued across move")
        publishTemplate(tenant, letters, textModel())
        withMediator { mediator.releaseNext(catalogId(tenant, letters)) }
        val queued = withMediator { request(tenant, letters, environment = null) }

        move(tenant, address(CatalogResourceType.TEMPLATE, letters, "invoice").movedTo(shared))

        assertGenerates(tenant, queued)
    }

    private fun request(tenant: TenantKey, catalog: CatalogKey, environment: EnvironmentKey?) = GenerateDocument(
        tenantId = tenant,
        catalogKey = catalog,
        templateId = TemplateKey.of("invoice"),
        variantId = VariantKey.INITIAL,
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
