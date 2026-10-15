// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.batch.DocumentGenerationExecutor
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetDocument
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.fonts.FontByteCache
import app.epistola.suite.fonts.FontSnapshotVerifier
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.DataExamples
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Generation renders a catalog release, bound when the request is accepted; preview renders the
 * latest release too, or the working copy when it asks for it.
 */
class GenerateFromReleaseTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var releaseAssembler: app.epistola.suite.catalog.revisions.ReleaseContentAssembler

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var contentStore: DocumentContentStore

    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Autowired private lateinit var generationService: GenerationService

    @Autowired private lateinit var schemaValidator: JsonSchemaValidator

    @Autowired private lateinit var fontSnapshotVerifier: FontSnapshotVerifier

    @Autowired private lateinit var fontByteCache: FontByteCache

    @Autowired private lateinit var localeResolver: TenantLocaleResolver

    @Autowired private lateinit var releaseRenderSource: ReleaseRenderSource

    private val realExecutor by lazy {
        DocumentGenerationExecutor(
            jdbi, generationService, mediator, objectMapper, schemaValidator, contentStore,
            meterRegistry, fontSnapshotVerifier, fontByteCache, localeResolver, releaseRenderSource,
            retentionDays = 7, maxDocumentSizeMb = 50,
        )
    }

    private fun contract(required: String): ObjectNode = objectMapper.readValue(
        """{"type": "object", "properties": {"$required": {"type": "string"}}, "required": ["$required"]}""",
        ObjectNode::class.java,
    )

    @Test
    fun `a request renders the release that was latest when it was accepted`(): Unit = scenario {
        given {
            val tenant = tenant("Release binding")
            val templateId = TemplateId(template(tenant.id, "Letter").id, CatalogId.default(TenantId(tenant.id)))
            val variant = VariantId(variant(templateId, "Default").id, templateId)

            // 1.0.0 requires `name`.
            execute(UpdateContractVersion(templateId, dataModel = contract("name"), dataExamples = listOf(DataExample("e", "E", objectMapper.createObjectNode().put("name", "Ada")))))
            released(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            Triple(tenant, templateId, variant)
        }.whenever { (tenant, templateId, variant) ->
            val accepted = execute(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = templateId.key,
                    variantId = variant.key,
                    data = objectMapper.createObjectNode().put("name", "Ada"),
                    filename = "letter.pdf",
                ),
            )
            // 1.0.1 requires a field the accepted request does not carry. Rendering 1.0.1 would fail
            // validation; rendering the release the request was accepted against succeeds.
            // A new contract draft (the last one was published with 1.0.0), published even though it breaks.
            execute(CreateContractVersion(templateId, dataModel = contract("reference"), dataExamples = DataExamples(listOf(DataExample("e", "E", objectMapper.createObjectNode().put("reference", "R-1"))))))
            execute(PublishContractVersion(templateId, confirmed = true))
            execute(UpdateDraft(variant, TestTemplateBuilder.buildMinimal(name = "Letter")))
            mediator.publishAndRelease(variant)
            accepted
        }.then { _, accepted ->
            // The premise: the release cut after acceptance really does require the other field.
            val laterContract = releaseAssembler.readResource(accepted.tenantKey, accepted.catalogKey, "1.0.1", "template", accepted.templateKey.value)
                as app.epistola.catalog.protocol.TemplateResource
            assertThat(laterContract.dataModel?.get("required")).isEqualTo(listOf("reference"))

            assertThat(accepted.releaseVersion).isEqualTo("1.0.0")
            assertThat(accepted.versionKey).isNull()

            // Integration tests wire a fake executor that never renders, so run the production one.
            realExecutor.execute(accepted)

            val job = mediator.query(GetGenerationJob(accepted.tenantKey, accepted.id))!!
            assertThat(job.request.status).`as`(job.request.errorMessage ?: "").isEqualTo(RequestStatus.COMPLETED)
            val document = mediator.query(GetDocument(accepted.tenantKey, job.items.single().documentKey!!))!!
            assertThat(document.releaseVersion).isEqualTo("1.0.0")
            assertThat(document.versionKey).isNull()
        }
    }

    @Test
    fun `a request accepted after a new release renders the new one`(): Unit = scenario {
        given {
            val tenant = tenant("Release latest")
            val templateId = TemplateId(template(tenant.id, "Letter").id, CatalogId.default(TenantId(tenant.id)))
            val variant = VariantId(variant(templateId, "Default").id, templateId)
            released(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            released(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            Triple(tenant, templateId, variant)
        }.whenever { (tenant, templateId, variant) ->
            execute(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = templateId.key,
                    variantId = variant.key,
                    data = objectMapper.createObjectNode(),
                    filename = null,
                ),
            )
        }.then { _, accepted ->
            assertThat(accepted.releaseVersion).isEqualTo("1.0.1")
        }
    }

    @Test
    fun `a request naming an environment renders the release that environment serves, not the latest`(): Unit = scenario {
        given {
            val tenant = tenant("Release by environment")
            val templateId = TemplateId(template(tenant.id, "Letter").id, CatalogId.default(TenantId(tenant.id)))
            val variant = VariantId(variant(templateId, "Default").id, templateId)
            released(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            val production = app.epistola.suite.common.ids.EnvironmentId(app.epistola.suite.common.ids.EnvironmentKey.of("production"), TenantId(tenant.id))
            execute(app.epistola.suite.environments.commands.CreateEnvironment(production, "Production"))
            execute(app.epistola.suite.environments.commands.DeployRelease(production, CatalogKey.DEFAULT, "1.0.0"))
            // A newer release exists, but production has not been moved to it.
            released(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            Triple(tenant, templateId, variant)
        }.whenever { (tenant, templateId, variant) ->
            execute(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = templateId.key,
                    variantId = variant.key,
                    environmentId = app.epistola.suite.common.ids.EnvironmentKey.of("production"),
                    data = objectMapper.createObjectNode(),
                    filename = null,
                ),
            )
        }.then { _, accepted ->
            assertThat(accepted.releaseVersion).isEqualTo("1.0.0")
            assertThat(accepted.environmentKey?.value).isEqualTo("production")
        }
    }

    @Test
    fun `a request naming a release renders that release, not the latest`(): Unit = scenario {
        given {
            val tenant = tenant("Named release")
            val templateId = TemplateId(template(tenant.id, "Letter").id, CatalogId.default(TenantId(tenant.id)))
            val variant = VariantId(variant(templateId, "Default").id, templateId)
            released(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            released(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            Triple(tenant, templateId, variant)
        }.whenever { (tenant, templateId, variant) ->
            execute(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = templateId.key,
                    variantId = variant.key,
                    releaseVersion = "1.0.0",
                    data = objectMapper.createObjectNode(),
                    filename = null,
                ),
            )
        }.then { _, accepted ->
            assertThat(accepted.releaseVersion).`as`("the named release, though 1.0.1 is newer").isEqualTo("1.0.0")
        }
    }

    @Test
    fun `a request naming an environment that serves nothing of the catalog is refused`(): Unit = scenario {
        given {
            val tenant = tenant("Release by environment, none")
            val templateId = TemplateId(template(tenant.id, "Letter").id, CatalogId.default(TenantId(tenant.id)))
            val variant = VariantId(variant(templateId, "Default").id, templateId)
            released(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            execute(
                app.epistola.suite.environments.commands.CreateEnvironment(
                    app.epistola.suite.common.ids.EnvironmentId(app.epistola.suite.common.ids.EnvironmentKey.of("production"), TenantId(tenant.id)),
                    "Production",
                ),
            )
            Triple(tenant, templateId, variant)
        }.whenever { it }
            .then { (tenant, templateId, variant), _ ->
                assertThatThrownBy {
                    execute(
                        GenerateDocument(
                            tenantId = tenant.id,
                            templateId = templateId.key,
                            variantId = variant.key,
                            environmentId = app.epistola.suite.common.ids.EnvironmentKey.of("production"),
                            data = objectMapper.createObjectNode(),
                            filename = null,
                        ),
                    )
                }.isInstanceOf(NoReleaseDeployedException::class.java)
            }
    }

    @Test
    fun `preview without a release is refused, unless it asks for the working copy`(): Unit = scenario {
        given {
            val tenant = tenant("Preview working copy")
            val templateId = TemplateId(template(tenant.id, "Letter").id, CatalogId.default(TenantId(tenant.id)))
            val variant = VariantId(variant(templateId, "Default").id, templateId)
            version(variant, TestTemplateBuilder.buildMinimal(name = "Letter"))
            Triple(tenant, templateId, variant)
        }.whenever { it }
            .then { (tenant, templateId, variant), _ ->
                fun preview(workingCopy: Boolean) = query(
                    PreviewDocument(
                        tenantId = tenant.id,
                        catalogKey = CatalogKey.DEFAULT,
                        templateId = templateId.key,
                        data = objectMapper.createObjectNode(),
                        variantId = variant.key,
                        workingCopy = workingCopy,
                    ),
                )

                assertThatThrownBy { preview(workingCopy = false) }.isInstanceOf(CatalogNotReleasedException::class.java)
                assertThat(preview(workingCopy = true).take(4).toByteArray()).isEqualTo(byteArrayOf(0x25, 0x50, 0x44, 0x46))
            }
    }
}
