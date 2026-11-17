// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.generation.pdf.GenerationContextXmp
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.batch.DocumentGenerationExecutor
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.generation.DraftDocumentRenderer
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.testCatalog
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.xmp.XMPMeta
import com.itextpdf.kernel.xmp.XMPMetaFactory
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream

/**
 * A generated document carries where it came from in its XMP (`docs/generation.md`, "Generation
 * context"), with the values the request recorded on acceptance: read back from the stored PDF as
 * an integrator would read it. The XMP's shape and PDF/A conformance are tested in
 * `modules/generation` (`GenerationContextXmpTest`, `PdfConformanceTest`).
 */
class GenerationContextIntegrationTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()
    private val ns = GenerationContextXmp.NAMESPACE

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var contentStore: DocumentContentStore

    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Autowired private lateinit var generationService: GenerationService

    @Autowired private lateinit var schemaValidator: JsonSchemaValidator

    @Autowired private lateinit var localeResolver: TenantLocaleResolver

    @Autowired private lateinit var releaseRenderSource: ReleaseRenderSource

    @Autowired private lateinit var draftRenderer: DraftDocumentRenderer

    // Integration tests wire a fake executor that never renders, so run the production one.
    private val realExecutor by lazy {
        DocumentGenerationExecutor(
            jdbi, generationService, mediator, objectMapper, schemaValidator, contentStore,
            meterRegistry, localeResolver, releaseRenderSource, draftRenderer,
            retentionDays = 7, maxDocumentSizeMb = 50,
        )
    }

    private val production = EnvironmentKey.of("production")
    private val test = EnvironmentKey.of("test")

    /** A letter released as 1.0.0 and 1.0.1, both served by `production` and `test`. */
    private fun setup(name: String): VariantId = withMediator {
        val tenant = TenantId(createTenant(name).id)
        val catalog = testCatalog(tenant, CatalogKey.DEFAULT)
        val templateId = TemplateId(CreateDocumentTemplate(TemplateId(TemplateKey.of("letter"), catalog), "Letter", catalogVersion = null).execute().id, catalog)
        val variant = VariantId(VariantKey.of("standard"), templateId)
        CreateVariant(id = variant, title = "Standard", description = null, attributes = emptyMap(), catalogVersion = null).execute()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter"), catalogVersion = null).execute()
        mediator.publishAndRelease(variant)
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter 2"), catalogVersion = null).execute()
        mediator.publishAndRelease(variant)
        for (key in listOf(production, test)) {
            CreateEnvironment(EnvironmentId(key, tenant), key.value, production = key == production).execute()
            DeployCatalogRelease(EnvironmentId(key, tenant), catalog, "1.0.0").execute()
            DeployCatalogRelease(EnvironmentId(key, tenant), catalog, "1.0.1").execute()
        }
        variant
    }

    private val VariantId.tenant: TenantKey get() = templateId.tenantKey

    private fun generate(variant: VariantId, environment: EnvironmentKey, correlationId: String? = null): DocumentGenerationRequest = withMediator {
        GenerateDocument(
            catalog = variant.templateId.catalogId,
            templateId = variant.templateId.key,
            variantId = variant.key,
            environmentId = environment,
            data = objectMapper.createObjectNode().put("secret", "BSN 123456782"),
            filename = null,
            correlationId = correlationId,
        ).execute()
    }

    /** Renders [request] with the production executor and returns the stored PDF. */
    private fun render(request: DocumentGenerationRequest): ByteArray {
        withMediator { realExecutor.execute(request) }
        val job = withMediator { GetGenerationJob(request.tenantKey, request.id).query()!! }
        assertThat(job.request.status).`as`(job.request.errorMessage ?: "").isEqualTo(RequestStatus.COMPLETED)
        return contentStore.get(ContentKey.document(request.tenantKey, job.items.single().documentKey!!))!!.content.readAllBytes()
    }

    private fun xmpOf(pdf: ByteArray): XMPMeta = PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { XMPMetaFactory.parseFromBuffer(it.xmpMetadataBytes) }

    @Test
    fun `a document generated from a release names the release, the request and how it was rendered`() {
        val variant = setup("context-release")
        val accepted = generate(variant, production, correlationId = "case-2026-0042")

        val pdf = render(accepted)
        val xmp = xmpOf(pdf)

        assertThat(xmp.getPropertyString(ns, "tenant")).isEqualTo(variant.tenant.value)
        assertThat(xmp.getPropertyString(ns, "registry")).isEqualTo(accepted.registry).isNotNull()
        assertThat(xmp.getPropertyString(ns, "namespace")).isEqualTo(variant.templateId.catalogId.namespace)
        assertThat(xmp.getPropertyString(ns, "catalog")).isEqualTo(CatalogKey.DEFAULT.value)
        assertThat(xmp.getPropertyString(ns, "template")).isEqualTo("letter")
        assertThat(xmp.getPropertyString(ns, "variant")).isEqualTo("standard")
        assertThat(xmp.getPropertyString(ns, "release")).isEqualTo("1.0.1")
        assertThat(xmp.getPropertyString(ns, "catalogVersion")).isEqualTo(accepted.catalogVersionId).isNotNull()
        assertThat(xmp.getPropertyString(ns, "environment")).isEqualTo("production")
        assertThat(xmp.getPropertyString(ns, "request")).isEqualTo(accepted.id.value.toString())
        assertThat(xmp.getProperty(ns, "batch")).isNull()
        assertThat(xmp.getProperty(ns, "draft")).isNull()
        assertThat(xmp.getPropertyBoolean(ns, "preview")).isFalse()
        assertThat(xmp.getPropertyString(ns, "locale")).isEqualTo(withMediator { localeResolver.resolve(variant.tenant) })
        assertThat(xmp.getPropertyString(ns, "engine")).startsWith("epistola-gen-")
        assertThat(xmp.getPropertyString(ns, "renderedAt")).isNotBlank()

        val raw = String(pdf, Charsets.ISO_8859_1)
        assertThat(raw).`as`("the caller's correlation id stays out of the PDF").doesNotContain("case-2026-0042")
        assertThat(raw).`as`("so does the submitted data").doesNotContain("123456782")
    }

    @Test
    fun `a document generated from a draft names the draft instead of a release`() {
        val variant = setup("context-draft")
        val draftId = withMediator { GetCatalogDraft(variant.templateId.catalogId).query()!!.versionId }
        withMediator { DeployCatalogRelease(EnvironmentId(test, TenantId(variant.tenant)), variant.templateId.catalogId, draftId).execute() }
        val accepted = generate(variant, test)
        assertThat(accepted.draftId).`as`("the premise: the environment serves the draft").isEqualTo(draftId)

        val xmp = xmpOf(render(accepted))

        assertThat(xmp.getPropertyString(ns, "draft")).isEqualTo(draftId)
        assertThat(xmp.getPropertyString(ns, "draftLabel")).isEqualTo(accepted.draftLabel).isNotNull()
        assertThat(xmp.getPropertyString(ns, "catalogVersion")).isEqualTo(draftId)
        assertThat(xmp.getProperty(ns, "release")).isNull()
        assertThat(xmp.getPropertyString(ns, "request")).isEqualTo(accepted.id.value.toString())
    }

    @Test
    fun `a document generated in a batch names the batch`() {
        val variant = setup("context-batch")
        val batch = withMediator {
            GenerateDocumentBatch(
                variant.tenant,
                listOf(BatchGenerationItem(templateId = variant.templateId.key, variantId = variant.key, data = objectMapper.createObjectNode(), filename = null, environmentId = production, catalog = variant.templateId.catalogId)),
            ).execute()
        }
        val request = withMediator { ListGenerationJobs(variant.tenant).query() }.single { it.batchId == batch }

        val xmp = xmpOf(render(request))

        assertThat(xmp.getPropertyString(ns, "batch")).isEqualTo(batch.value.toString())
        assertThat(xmp.getPropertyString(ns, "request")).isEqualTo(request.id.value.toString())
    }

    @Test
    fun `a preview of a release says it is a preview, and names no request`() {
        val variant = setup("context-preview")

        val pdf = withMediator {
            PreviewDocument(variant.templateId.catalogId, variant.templateId.key, objectMapper.createObjectNode(), variantId = variant.key, environmentId = production, catalogVersion = null).query()
        }
        val xmp = xmpOf(pdf)

        assertThat(xmp.getPropertyBoolean(ns, "preview")).isTrue()
        assertThat(xmp.getPropertyString(ns, "release")).isEqualTo("1.0.1")
        assertThat(xmp.getPropertyString(ns, "template")).isEqualTo("letter")
        assertThat(xmp.getPropertyString(ns, "variant")).isEqualTo("standard")
        assertThat(xmp.getPropertyString(ns, "environment")).isEqualTo("production")
        assertThat(xmp.getProperty(ns, "request")).isNull()
    }
}
