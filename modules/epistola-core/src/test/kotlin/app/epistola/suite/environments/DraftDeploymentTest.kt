// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.commands.ContinueEverydayWork
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.catalog.versions.GetCatalogDraft
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
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.environments.commands.UndeployCatalogVersion
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.GetEnvironmentLatest
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
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
class DraftDeploymentTest : IntegrationTestBase() {

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
            DeployCatalogVersion(EnvironmentId(key, TenantId(tenant)), default, "1.0.0").execute()
            DeployCatalogVersion(EnvironmentId(key, TenantId(tenant)), default, "1.0.1").execute()
        }
        Setup(tenant, variant)
    }

    private fun env(setup: Setup, key: EnvironmentKey) = EnvironmentId(key, TenantId(setup.tenant))

    private fun nextVersion(setup: Setup): String = withMediator { GetCatalogDraft(setup.tenant, default).query()!!.versionId }

    /** Deploys draft [draftId] (the next version by default) to [environment]. */
    private fun deployDraft(setup: Setup, environment: EnvironmentKey, draftId: String = nextVersion(setup)) = withMediator { DeployCatalogVersion(env(setup, environment), default, draftId).execute() }

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
    fun `a request naming no version gets the highest release served, and rolling back is undeploying the newer one`() {
        val setup = setup("deploy-rollback")
        assertThat(generate(setup, production).releaseVersion).`as`("the highest it serves").isEqualTo("1.0.1")
        assertThat(generate(setup, production, "1.0.0").releaseVersion).`as`("an exact release").isEqualTo("1.0.0")

        withMediator { UndeployCatalogVersion(env(setup, production), default, "1.0.1").execute() }

        assertThat(generate(setup, production).releaseVersion).isEqualTo("1.0.0")
        assertThat(generate(setup, production, "^1.0.0").releaseVersion).isEqualTo("1.0.0")
        assertThat(generate(setup, test).releaseVersion).`as`("other environments are unaffected").isEqualTo("1.0.1")
    }

    @Test
    fun `a draft is deployed by its id, at most one per catalog, and another replaces it in the history`() {
        val setup = setup("deploy-draft-replace")
        val work = withMediator { CreateCatalogDraft(setup.tenant, default, name = "Redesign").execute() }
        deployDraft(setup, test)

        val replaced = deployDraft(setup, test, work.versionId)

        assertThat(replaced.isDraft).isTrue()
        assertThat(replaced.draftName).isEqualTo("Redesign")
        assertThat(withMediator { ListDeployments(setup.tenant, default, test).query() }.map { it.version ?: it.draftName })
            .`as`("the draft first, then the releases").containsExactly("Redesign", "1.0.1", "1.0.0")
        assertThat(withMediator { ListDeploymentHistory(env(setup, test)).query() }.take(3).map { Triple(it.action, it.version, it.draftLabel) }).containsExactly(
            Triple(DeploymentAction.DEPLOYED, null, "Redesign"),
            Triple(DeploymentAction.UNDEPLOYED, null, "Next version"),
            Triple(DeploymentAction.DEPLOYED, null, "Next version"),
        )
        assertThat(withMediator { UndeployCatalogVersion(env(setup, test), default, work.versionId).execute() }).isTrue()
        assertThat(generate(setup, test).releaseVersion).isEqualTo("1.0.1")
    }

    @Test
    fun `a deployed draft renders live for a request naming no version, and its documents say so`() {
        val setup = setup("override-draft")
        deployDraft(setup, test)
        // The draft now asks for another field than every release: only a render of the draft wants it.
        withMediator { UpdateDataContract(setup.variant.templateId, dataModel = contract("reference"), dataExamples = listOf(DataExample("e", "E", data("reference"))), forceUpdate = true).execute() }

        val accepted = generate(setup, test, field = "reference")
        assertThat(accepted.releaseVersion).isNull()
        assertThat(accepted.draftLabel).isEqualTo("Next version")
        assertThat(generate(setup, test, "^1.0.0").releaseVersion).`as`("a range matches releases only").isEqualTo("1.0.1")
        assertThat(generate(setup, test, "1.0.1").releaseVersion).`as`("so does a number").isEqualTo("1.0.1")

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
    fun `a batch through an environment serving a draft renders the draft`() {
        val setup = setup("override-batch")
        deployDraft(setup, test)

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
    fun `production serves only releases`() {
        val setup = setup("override-production")
        assertThatThrownBy { deployDraft(setup, production) }
            .isInstanceOf(EnvironmentDeploymentRefusedException::class.java)
            .hasMessageContaining("production")

        deployDraft(setup, test)
        assertThatThrownBy { withMediator { UpdateEnvironment(env(setup, test), "test", production = true).execute() } }
            .`as`("marking it production while it serves a draft")
            .isInstanceOf(EnvironmentDeploymentRefusedException::class.java)
            .hasMessageContaining("Next version")
    }

    @Test
    fun `discarding a deployed draft undeploys it, recorded in the history`() {
        val setup = setup("override-discard")
        val work = withMediator { CreateCatalogDraft(setup.tenant, default, name = "Redesign").execute() }
        deployDraft(setup, test, work.versionId)
        assertThat(generate(setup, test).draftLabel).isEqualTo("Redesign")

        withMediator { DeleteCatalogDraft(setup.tenant, default, DraftRef.Id(work.versionId)).execute() }

        assertThat(withMediator { ListDeployments(setup.tenant, default, test).query() }.none { it.isDraft }).isTrue()
        assertThat(generate(setup, test).releaseVersion).isEqualTo("1.0.1")
        assertThat(withMediator { ListDeploymentHistory(env(setup, test)).query() }.take(2).map { Triple(it.action, it.version, it.draftLabel) }).containsExactly(
            Triple(DeploymentAction.UNDEPLOYED, null, "Redesign"),
            Triple(DeploymentAction.DEPLOYED, null, "Redesign"),
        )
    }

    @Test
    fun `a deployment names a draft by its id, so another draft taking over the next version does not move it`() {
        val setup = setup("override-promote")
        val next = deployDraft(setup, test)
        val work = withMediator { CreateCatalogDraft(setup.tenant, default, name = "Redesign").execute() }

        withMediator {
            val at = CatalogVersionRef(setup.tenant, default, DraftRef.Id(work.versionId))
            MarkCatalogReady(setup.tenant, default, catalogVersion = at).execute()
            ReleaseCatalogVersion(setup.tenant, default, catalogVersion = at, continueEverydayWork = ContinueEverydayWork(keepPreviousAs = "Old next")).execute()
        }

        val deployed = withMediator { ListDeployments(setup.tenant, default, test).query() }.single { it.isDraft }
        assertThat(deployed.versionId).isEqualTo(next.versionId)
        assertThat(deployed.draftName).isEqualTo("Old next")
    }

    @Test
    fun `what each environment uses for no version is the draft it serves, else its highest release`() {
        val setup = setup("override-latest")
        deployDraft(setup, test)

        assertThat(withMediator { GetEnvironmentLatest(setup.tenant, production).query() })
            .isEqualTo(mapOf(default to CatalogSelection.Release("1.0.1")))
        assertThat((withMediator { GetEnvironmentLatest(setup.tenant, test).query() }.getValue(default) as CatalogSelection.Draft).source.label)
            .isEqualTo("Next version")

        withMediator { UndeployCatalogVersion(env(setup, test), default, nextVersion(setup)).execute() }
        assertThat(withMediator { GetEnvironmentLatest(setup.tenant, test).query() })
            .isEqualTo(mapOf(default to CatalogSelection.Release("1.0.1")))
    }

    @Test
    fun `analysing data through an environment serving a draft checks the draft's contract`() {
        val setup = setup("override-analyze")
        deployDraft(setup, test)
        withMediator { UpdateDataContract(setup.variant.templateId, dataModel = contract("reference"), dataExamples = listOf(DataExample("e", "E", data("reference"))), forceUpdate = true).execute() }
        fun analyze(environment: EnvironmentKey) = withMediator {
            AnalyzeTemplateData(setup.tenant, default, setup.variant.templateId.key, data("name"), variantId = setup.variant.key, environmentId = environment).query()
        }

        assertThat(analyze(production).valid).`as`("production's release asks for name").isTrue()
        assertThat(analyze(test).valid).`as`("test's draft asks for reference").isFalse()
    }

    @Test
    fun `releasing a deployed draft keeps the deployment, as the release that keeps its id, logged as released`() {
        val setup = setup("deploy-released")
        val work = withMediator { CreateCatalogDraft(setup.tenant, default, name = "Redesign").execute() }
        val ref = CatalogVersionRef(setup.tenant, default, DraftRef.Id(work.versionId))
        withMediator {
            app.epistola.suite.templates.commands.variants.UpdateVariantContent(setup.variant, TestTemplateBuilder.buildMinimal(name = "Letter 3"), catalogVersion = ref).execute()
            MarkCatalogReady(setup.tenant, default, catalogVersion = ref).execute()
        }
        deployDraft(setup, test, work.versionId)

        val released = withMediator { ReleaseCatalogVersion(setup.tenant, default, catalogVersion = ref).execute() }

        val deployed = withMediator { ListDeployments(setup.tenant, default, test).query() }.first()
        assertThat(deployed.isDraft).isFalse()
        assertThat(deployed.version).isEqualTo(released.version)
        assertThat(deployed.versionId).`as`("the release keeps the draft's id").isEqualTo(work.versionId)
        assertThat(withMediator { ListDeploymentHistory(env(setup, test)).query() }.first().let { Triple(it.action, it.version, it.draftLabel) })
            .isEqualTo(Triple(DeploymentAction.RELEASED, released.version, "Redesign"))
        assertThat(generate(setup, test).releaseVersion).isEqualTo(released.version)
    }

    @Test
    fun `a document from a draft is rendered outside any transaction, each font read on its own`() {
        val setup = setup("override-draft-tx")
        withMediator {
            val theme = ThemeId(ThemeKey.of("house"), CatalogId(default, TenantId(setup.tenant)))
            CreateTheme(theme, "House", documentStyles = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value))).execute()
            UpdateDocumentTemplate(setup.variant.templateId, themeId = theme.key, themeCatalogKey = default).execute()
            // Using the system font pinned the system release, whose font the draft reads from that
            // release. Undeclared, the system catalog is read live, through the font query this watches.
            UpdateCatalogDependencies(setup.tenant, default, emptyList()).execute()
        }
        deployDraft(setup, test)
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
