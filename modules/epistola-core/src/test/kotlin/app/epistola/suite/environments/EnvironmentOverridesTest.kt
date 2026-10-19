// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.catalog.versions.PromoteCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.batch.DocumentGenerationExecutor
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.AnalyzeTemplateData
import app.epistola.suite.documents.queries.GetDocument
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.commands.UndeployRelease
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.GetEnvironmentLatest
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.fonts.FontByteCache
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.generation.release.CatalogSelection
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.DispatchOutcome
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryListener
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.themes.commands.CreateTheme
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * An environment's override: what it uses as its latest version of a catalog. A release it serves
 * (a rollback), or a draft, rendered live — never a draft on production. A request naming an exact
 * release is not affected; a range is.
 */
@Import(FontLookupProbe::class)
class EnvironmentOverridesTest : IntegrationTestBase() {

    @Autowired private lateinit var fontLookups: FontLookupProbe

    private val objectMapper = ObjectMapper()

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var contentStore: DocumentContentStore

    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Autowired private lateinit var generationService: GenerationService

    @Autowired private lateinit var schemaValidator: JsonSchemaValidator

    @Autowired private lateinit var localeResolver: TenantLocaleResolver

    @Autowired private lateinit var releaseRenderSource: ReleaseRenderSource

    @Autowired private lateinit var draftRenderer: app.epistola.suite.generation.DraftDocumentRenderer

    // Integration tests wire a fake executor that never renders, so run the production one.
    private val realExecutor by lazy {
        DocumentGenerationExecutor(
            jdbi, generationService, mediator, objectMapper, schemaValidator, contentStore,
            meterRegistry, localeResolver, releaseRenderSource, draftRenderer,
            retentionDays = 7, maxDocumentSizeMb = 50,
        )
    }

    private val default = CatalogKey.DEFAULT
    private val test = EnvironmentKey.of("test")
    private val production = EnvironmentKey.of("production")

    private data class Setup(val tenant: TenantKey, val variant: VariantId)

    private fun contract(required: String): ObjectNode = objectMapper.readValue(
        """{"type": "object", "properties": {"$required": {"type": "string"}}, "required": ["$required"]}""",
        ObjectNode::class.java,
    )

    private fun data(field: String) = objectMapper.createObjectNode().put(field, "x")

    /** A letter released as 1.0.0 and 1.0.1 (both requiring `name`), served by `test` and `production`. */
    private fun setup(name: String): Setup = withMediator {
        val tenant = createTenant(name).id
        val templateId = TemplateId(app.epistola.suite.templates.commands.CreateDocumentTemplate(TemplateId(app.epistola.suite.common.ids.TemplateKey.of("letter"), CatalogId(default, TenantId(tenant))), "Letter").execute().id, CatalogId(default, TenantId(tenant)))
        val variant = VariantId(app.epistola.suite.common.ids.VariantKey.of("standard"), templateId)
        CreateVariant(id = variant, title = "Standard", description = null, attributes = emptyMap()).execute()
        UpdateDataContract(templateId, dataModel = contract("name"), dataExamples = listOf(DataExample("e", "E", data("name")))).execute()
        app.epistola.suite.templates.commands.variants.UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter")).execute()
        mediator.publishAndRelease(variant)
        app.epistola.suite.templates.commands.variants.UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter 2")).execute()
        mediator.publishAndRelease(variant)
        for (key in listOf(test, production)) {
            CreateEnvironment(EnvironmentId(key, TenantId(tenant)), key.value, production = key == production).execute()
            DeployRelease(EnvironmentId(key, TenantId(tenant)), default, "1.0.0").execute()
            DeployRelease(EnvironmentId(key, TenantId(tenant)), default, "1.0.1").execute()
        }
        Setup(tenant, variant)
    }

    private fun env(setup: Setup, key: EnvironmentKey) = EnvironmentId(key, TenantId(setup.tenant))

    private fun generate(setup: Setup, environment: EnvironmentKey?, selector: String? = null, field: String = "name"): DocumentGenerationRequest = withMediator {
        GenerateDocument(
            tenantId = setup.tenant,
            templateId = setup.variant.templateId.key,
            variantId = setup.variant.key,
            environmentId = environment,
            releaseVersion = selector,
            data = data(field),
            filename = null,
        ).execute()
    }

    @Test
    fun `a release override is what the environment uses for no version and for a range, not for an exact release`() {
        val setup = setup("override-release")
        assertThat(generate(setup, production).releaseVersion).`as`("the highest it serves").isEqualTo("1.0.1")

        withMediator { SetEnvironmentOverride(env(setup, production), default, version = "1.0.0").execute() }

        assertThat(generate(setup, production).releaseVersion).isEqualTo("1.0.0")
        assertThat(generate(setup, production, "^1.0.0").releaseVersion).`as`("a range gets the override").isEqualTo("1.0.0")
        assertThat(generate(setup, production, "1.0.1").releaseVersion).`as`("an exact release is still served").isEqualTo("1.0.1")
        assertThat(generate(setup, test).releaseVersion).`as`("other environments are unaffected").isEqualTo("1.0.1")
        assertThat(generate(setup, null).releaseVersion).`as`("so are requests without an environment").isEqualTo("1.0.1")

        assertThat(withMediator { ClearEnvironmentOverride(env(setup, production), default).execute() }).isTrue()
        assertThat(generate(setup, production).releaseVersion).isEqualTo("1.0.1")
    }

    @Test
    fun `an override names a release the environment serves, and undeploying it ends the override`() {
        val setup = setup("override-served")
        withMediator { UndeployRelease(env(setup, test), default, "1.0.0").execute() }
        assertThatThrownBy { withMediator { SetEnvironmentOverride(env(setup, test), default, version = "1.0.0").execute() } }
            .isInstanceOf(EnvironmentOverrideRefusedException::class.java)
            .hasMessageContaining("deploy it there first")

        withMediator { SetEnvironmentOverride(env(setup, production), default, version = "1.0.0").execute() }
        withMediator { UndeployRelease(env(setup, production), default, "1.0.0").execute() }

        assertThat(withMediator { ListEnvironmentOverrides(setup.tenant, production).query() }).isEmpty()
        assertThat(generate(setup, production).releaseVersion).isEqualTo("1.0.1")
    }

    @Test
    fun `an environment following a draft renders it live, and its documents say so`() {
        val setup = setup("override-draft")
        withMediator { SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Next).execute() }
        // The draft now asks for another field than every release: only a render of the draft wants it.
        withMediator { UpdateDataContract(setup.variant.templateId, dataModel = contract("reference"), dataExamples = listOf(DataExample("e", "E", data("reference"))), forceUpdate = true).execute() }

        val accepted = generate(setup, test, field = "reference")
        assertThat(accepted.releaseVersion).isNull()
        assertThat(accepted.draftLabel).isEqualTo("Next version")
        assertThat(generate(setup, test, "^1.0.0", field = "reference").draftId).`as`("a range gets the draft too").isEqualTo(accepted.draftId)
        assertThat(generate(setup, test, "1.0.1").releaseVersion).`as`("an exact release is still served").isEqualTo("1.0.1")

        withMediator { realExecutor.execute(accepted) }
        val job = withMediator { GetGenerationJob(setup.tenant, accepted.id).query()!! }
        assertThat(job.request.status).`as`(job.request.errorMessage ?: "").isEqualTo(RequestStatus.COMPLETED)
        val document = withMediator { GetDocument(setup.tenant, job.items.single().documentKey!!).query()!! }
        assertThat(document.releaseVersion).isNull()
        assertThat(document.draftId).isEqualTo(accepted.draftId)
        assertThat(document.draftLabel).isEqualTo("Next version")

        // Data the release wants but the draft does not: the draft is what is validated.
        val stale = generate(setup, test, field = "name")
        withMediator { realExecutor.execute(stale) }
        assertThat(withMediator { GetGenerationJob(setup.tenant, stale.id).query()!! }.request.errorMessage).contains("reference")

        val preview = withMediator {
            PreviewDocument(setup.tenant, default, setup.variant.templateId.key, data("reference"), variantId = setup.variant.key, environmentId = test).query()
        }
        assertThat(String(preview, 0, 5)).`as`("previewing through the environment renders the draft").isEqualTo("%PDF-")
    }

    @Test
    fun `a batch through an environment following a draft renders the draft`() {
        val setup = setup("override-batch")
        withMediator { SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Next).execute() }

        val batch = withMediator {
            GenerateDocumentBatch(
                setup.tenant,
                listOf(BatchGenerationItem(templateId = setup.variant.templateId.key, variantId = setup.variant.key, data = data("name"), filename = null, environmentId = test)),
            ).execute()
        }

        val request = withMediator { ListGenerationJobs(setup.tenant).query() }.single { it.batchId == batch }
        assertThat(request.draftLabel).isEqualTo("Next version")
        assertThat(request.releaseVersion).isNull()
    }

    @Test
    fun `production uses only published versions`() {
        val setup = setup("override-production")
        assertThatThrownBy { withMediator { SetEnvironmentOverride(env(setup, production), default, draft = DraftRef.Next).execute() } }
            .isInstanceOf(EnvironmentOverrideRefusedException::class.java)
            .hasMessageContaining("production")

        withMediator { SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Next).execute() }
        assertThatThrownBy { withMediator { UpdateEnvironment(env(setup, test), "test", production = true).execute() } }
            .`as`("marking it production while it follows a draft")
            .isInstanceOf(EnvironmentOverrideRefusedException::class.java)
            .hasMessageContaining("Next version")
    }

    @Test
    fun `discarding the draft ends the override, and the history records each change`() {
        val setup = setup("override-discard")
        val work = withMediator { CreateCatalogDraft(setup.tenant, default, name = "Redesign").execute() }
        withMediator { SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Id(work.versionId)).execute() }
        assertThat(generate(setup, test).draftLabel).isEqualTo("Redesign")

        withMediator { DeleteCatalogDraft(setup.tenant, default, DraftRef.Id(work.versionId)).execute() }

        assertThat(withMediator { ListEnvironmentOverrides(setup.tenant, test).query() }).isEmpty()
        assertThat(generate(setup, test).releaseVersion).isEqualTo("1.0.1")
        withMediator { SetEnvironmentOverride(env(setup, test), default, version = "1.0.0").execute() }
        withMediator { ClearEnvironmentOverride(env(setup, test), default).execute() }
        val history = withMediator { ListDeploymentHistory(env(setup, test)).query() }
        assertThat(history.take(4).map { Triple(it.action, it.version, it.draftLabel) }).containsExactly(
            Triple(DeploymentAction.OVERRIDE_CLEARED, "1.0.0", null),
            Triple(DeploymentAction.OVERRIDE_SET, "1.0.0", null),
            Triple(DeploymentAction.OVERRIDE_CLEARED, null, "Redesign"),
            Triple(DeploymentAction.OVERRIDE_SET, null, "Redesign"),
        )
    }

    @Test
    fun `an override follows a draft by its id, so making another draft the next version does not move it`() {
        val setup = setup("override-promote")
        val next = withMediator { SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Next).execute() }
        val work = withMediator { CreateCatalogDraft(setup.tenant, default, name = "Redesign").execute() }

        withMediator { PromoteCatalogDraft(setup.tenant, default, DraftRef.Id(work.versionId), keepPreviousAs = "Old next").execute() }

        val override = withMediator { ListEnvironmentOverrides(setup.tenant, test).query() }.single()
        assertThat(override.draftId).isEqualTo(next.draftId)
        assertThat(override.draftLabel).isEqualTo("Old next")
    }

    @Test
    fun `what each environment uses is the highest release it serves, or its override`() {
        val setup = setup("override-latest")
        withMediator { SetEnvironmentOverride(env(setup, production), default, version = "1.0.0").execute() }
        withMediator { SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Next).execute() }

        assertThat(withMediator { GetEnvironmentLatest(setup.tenant, production).query() })
            .isEqualTo(mapOf(default to CatalogSelection.Release("1.0.0")))
        assertThat((withMediator { GetEnvironmentLatest(setup.tenant, test).query() }.getValue(default) as CatalogSelection.Draft).source.label)
            .isEqualTo("Next version")

        withMediator { ClearEnvironmentOverride(env(setup, production), default).execute() }
        assertThat(withMediator { GetEnvironmentLatest(setup.tenant, production).query() })
            .isEqualTo(mapOf(default to CatalogSelection.Release("1.0.1")))
    }

    @Test
    fun `analysing data through an environment following a draft checks the draft's contract`() {
        val setup = setup("override-analyze")
        withMediator { SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Next).execute() }
        withMediator { UpdateDataContract(setup.variant.templateId, dataModel = contract("reference"), dataExamples = listOf(DataExample("e", "E", data("reference"))), forceUpdate = true).execute() }
        fun analyze(environment: EnvironmentKey) = withMediator {
            AnalyzeTemplateData(setup.tenant, default, setup.variant.templateId.key, data("name"), variantId = setup.variant.key, environmentId = environment).query()
        }

        assertThat(analyze(production).valid).`as`("production's release asks for name").isTrue()
        assertThat(analyze(test).valid).`as`("test's draft asks for reference").isFalse()
    }

    @Test
    fun `an override that ends because its release is undeployed or its draft discarded is recorded`() {
        val setup = setup("override-ended")
        withMediator { SetEnvironmentOverride(env(setup, production), default, version = "1.0.0").execute() }
        withMediator { UndeployRelease(env(setup, production), default, "1.0.0").execute() }
        assertThat(withMediator { ListDeploymentHistory(env(setup, production)).query() }.take(2).map { Triple(it.action, it.version, it.draftLabel) })
            .containsExactly(Triple(DeploymentAction.OVERRIDE_CLEARED, "1.0.0", null), Triple(DeploymentAction.UNDEPLOYED, "1.0.0", null))

        val work = withMediator { CreateCatalogDraft(setup.tenant, default, name = "Redesign").execute() }
        withMediator { SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Id(work.versionId)).execute() }
        withMediator { DeleteCatalogDraft(setup.tenant, default, DraftRef.Id(work.versionId)).execute() }
        assertThat(withMediator { ListDeploymentHistory(env(setup, test)).query() }.first().let { Triple(it.action, it.version, it.draftLabel) })
            .isEqualTo(Triple(DeploymentAction.OVERRIDE_CLEARED, null, "Redesign"))

        withMediator { SetEnvironmentOverride(env(setup, test), default, version = "1.0.0").execute() }
        withMediator { DeployRelease(env(setup, test), default, "1.0.1", replace = true).execute() }
        assertThat(withMediator { ListDeploymentHistory(env(setup, test)).query() }.map { it.action to it.version })
            .`as`("deploying in its place ends it too").contains(DeploymentAction.OVERRIDE_CLEARED to "1.0.0")
        assertThat(withMediator { ListEnvironmentOverrides(setup.tenant, test).query() }).isEmpty()
    }

    @Test
    fun `a document from a draft is rendered outside any transaction, each font read on its own`() {
        val setup = setup("override-draft-tx")
        withMediator {
            val theme = ThemeId(ThemeKey.of("house"), CatalogId(default, TenantId(setup.tenant)))
            CreateTheme(theme, "House", documentStyles = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value))).execute()
            UpdateDocumentTemplate(setup.variant.templateId, themeId = theme.key, themeCatalogKey = default).execute()
            SetEnvironmentOverride(env(setup, test), default, draft = DraftRef.Next).execute()
        }
        val accepted = generate(setup, test)
        fontLookups.inTransaction.clear()

        withMediator { realExecutor.execute(accepted) }

        assertThat(withMediator { GetGenerationJob(setup.tenant, accepted.id).query()!! }.request.status).isEqualTo(RequestStatus.COMPLETED)
        assertThat(fontLookups.inTransaction).`as`("the theme's font was read while rendering").isNotEmpty()
        assertThat(fontLookups.inTransaction).`as`("no read during the render ran inside a transaction held for it").containsOnly(false)
    }
}

/** Records, for each font face lookup, whether a transaction was still open when it finished. */
@TestConfiguration
class FontLookupProbe : QueryListener {
    val inTransaction: MutableList<Boolean> = java.util.Collections.synchronizedList(mutableListOf())

    override fun onQuery(query: Query<*>, outcome: DispatchOutcome, error: Throwable?) {
        if (query is ResolveFontFace) inTransaction += TransactionSynchronizationManager.isActualTransactionActive()
    }
}
