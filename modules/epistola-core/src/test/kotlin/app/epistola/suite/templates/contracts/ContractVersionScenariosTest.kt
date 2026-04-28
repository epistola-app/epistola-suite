package app.epistola.suite.templates.contracts

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.contracts.model.ContractVersionStatus
import app.epistola.suite.templates.contracts.queries.CheckContractPublishImpact
import app.epistola.suite.templates.contracts.queries.CheckSchemaCompatibility
import app.epistola.suite.templates.contracts.queries.GetDraftContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestPublishedContractVersion
import app.epistola.suite.templates.contracts.queries.ListContractVersions
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * End-to-end scenario tests for contract schema versioning.
 *
 * Each test reads like a user story — describing a real workflow
 * from template creation through contract changes to deployment.
 */
@Timeout(30)
class ContractVersionScenariosTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()
    private lateinit var templateId: TemplateId
    private var tenantKey: TenantKey = TenantKey.of("placeholder")
    private lateinit var defaultVariantId: VariantId

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    @BeforeEach
    fun createTemplate() {
        val tenant = createTenant("Scenario Test Tenant")
        tenantKey = tenant.id
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        withMediator {
            CreateDocumentTemplate(id = templateId, name = "scenario-template").execute()
        }
        defaultVariantId = VariantId(
            VariantKey.of("${templateId.key.value}-default"),
            templateId,
        )
    }

    private fun createEnvironment(name: String): EnvironmentId {
        val tenantId = TenantId(tenantKey)
        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        withMediator { CreateEnvironment(id = envId, name = name).execute() }
        return envId
    }

    // =========================================================================
    // Scenario 1: New template, no contract work — just deploy
    // =========================================================================

    @Nested
    inner class NewTemplateWithoutContract {
        @Test
        fun `create template and deploy without touching the contract`() {
            val envId = createEnvironment("production")
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }

            // Deploy directly — empty contract auto-publishes
            val result = withMediator {
                PublishToEnvironment(
                    versionId = VersionId(draft.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }

            assertThat(result).isNotNull
            assertThat(result!!.version.status).isEqualTo(VersionStatus.PUBLISHED)

            // Contract was auto-published (empty)
            val publishedContract = withMediator {
                GetLatestPublishedContractVersion(templateId = templateId).query()
            }
            assertThat(publishedContract).isNotNull
            assertThat(publishedContract!!.dataModel).isNull()
        }
    }

    // =========================================================================
    // Scenario 2: Define contract, then deploy
    // =========================================================================

    @Nested
    inner class DefineContractThenDeploy {
        @Test
        fun `define a contract schema, then deploy the template`() {
            // User edits the contract
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"}},"required":["name"]}"""),
                ).execute()
            }

            // User deploys — compatible contract auto-publishes
            val envId = createEnvironment("staging")
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            val result = withMediator {
                PublishToEnvironment(
                    versionId = VersionId(draft.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }

            assertThat(result).isNotNull

            // Verify the published contract has the schema
            val published = withMediator {
                GetLatestPublishedContractVersion(templateId = templateId).query()
            }
            assertThat(published).isNotNull
            assertThat(published!!.dataModel).isNotNull
            assertThat(published.dataModel!!.has("properties")).isTrue()
        }
    }

    // =========================================================================
    // Scenario 3: Add optional field to existing contract — compatible change
    // =========================================================================

    @Nested
    inner class AddOptionalField {
        @Test
        fun `add optional field and deploy — auto-publishes compatible change`() {
            // Setup: publish a contract and deploy
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            val envId = createEnvironment("production")
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator {
                PublishToEnvironment(
                    versionId = VersionId(draft.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }

            // User adds an optional field
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}}}"""),
                ).execute()
            }

            // Check compatibility — should be compatible
            val compatibility = withMediator {
                CheckSchemaCompatibility(
                    templateId = templateId,
                    newSchema = schema("""{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}}}"""),
                ).query()
            }
            assertThat(compatibility.compatible).isTrue()

            // Create new version and deploy — contract auto-publishes
            val newDraft = withMediator { CreateVersion(defaultVariantId).execute()!! }
            val result = withMediator {
                PublishToEnvironment(
                    versionId = VersionId(newDraft.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }
            assertThat(result).isNotNull

            // No draft contract remains
            val draftContract = withMediator { GetDraftContractVersion(templateId = templateId).query() }
            assertThat(draftContract).isNull()
        }
    }

    // =========================================================================
    // Scenario 4: Breaking change — remove a field
    // =========================================================================

    @Nested
    inner class BreakingChangeRemoveField {
        @Test
        fun `deploying a version on the breaking draft contract is blocked`() {
            // Setup: publish contract with two fields and deploy
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}"""),
                ).execute()
            }
            val envId = createEnvironment("production")
            val v1 = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator {
                PublishToEnvironment(
                    versionId = VersionId(v1.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }

            // Create a new template version draft (previous was consumed by publish)
            withMediator { CreateVersion(defaultVariantId).execute() }

            // User creates a breaking draft (remove field)
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            // The draft template version was linked to the breaking contract draft
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            assertThat(draft.contractVersion).isEqualTo(VersionKey.of(2))

            // Deploying is blocked because the contract draft has breaking changes
            assertThatThrownBy {
                withMediator {
                    PublishToEnvironment(
                        versionId = VersionId(draft.id, defaultVariantId),
                        environmentId = envId,
                    ).execute()
                }
            }.hasMessageContaining("breaking changes")
        }

        @Test
        fun `user must explicitly publish the breaking contract first`() {
            // Setup: publish contract, then make a breaking change
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}"""),
                ).execute()
            }
            val envId = createEnvironment("production")
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator {
                PublishToEnvironment(
                    versionId = VersionId(draft.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }

            // Make breaking change
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            // Preview: shows it's breaking
            val preview = withMediator {
                PublishContractVersion(templateId = templateId, confirmed = false).execute()
            }
            assertThat(preview).isNotNull
            assertThat(preview!!.published).isFalse()
            assertThat(preview.compatible).isFalse()
            assertThat(preview.breakingChanges).isNotEmpty()

            // Confirm publish: succeeds
            val published = withMediator {
                PublishContractVersion(templateId = templateId, confirmed = true).execute()
            }
            assertThat(published).isNotNull
            assertThat(published!!.published).isTrue()

            // Now deploying works
            val newDraft = withMediator { CreateVersion(defaultVariantId).execute()!! }
            val result = withMediator {
                PublishToEnvironment(
                    versionId = VersionId(newDraft.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }
            assertThat(result).isNotNull
        }
    }

    // =========================================================================
    // Scenario 5: Publish impact analysis
    // =========================================================================

    @Nested
    inner class PublishImpactAnalysis {
        @Test
        fun `preview shows which template versions are affected by breaking change`() {
            // Setup: publish contract and deploy a version
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}"""),
                ).execute()
            }
            val envId = createEnvironment("production")
            val v1 = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator {
                PublishToEnvironment(
                    versionId = VersionId(v1.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }

            // Make breaking change (remove age)
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            // Check full publish impact
            val impact = withMediator {
                CheckContractPublishImpact(templateId = templateId).query()
            }
            assertThat(impact).isNotNull
            // Schema-level: breaking (field removed)
            assertThat(impact!!.breakingChanges).anyMatch { it.path == "age" }
            // Template-level: compatible (no template actually uses 'age' — empty default template)
            assertThat(impact.incompatibleVersions).isEmpty()
            assertThat(impact.compatible).isTrue()
        }
    }

    // =========================================================================
    // Scenario 6: On-demand draft lifecycle
    // =========================================================================

    @Nested
    inner class OnDemandDraftLifecycle {
        @Test
        fun `no draft exists after publishing`() {
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId, confirmed = true).execute() }

            val draft = withMediator { GetDraftContractVersion(templateId = templateId).query() }
            assertThat(draft).isNull()
        }

        @Test
        fun `CreateContractVersion creates draft by copying published`() {
            // Publish a contract with a schema
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId, confirmed = true).execute() }

            // Create a new draft — should copy from published
            val newDraft = withMediator {
                CreateContractVersion(templateId = templateId).execute()
            }
            assertThat(newDraft).isNotNull
            assertThat(newDraft!!.dataModel).isNotNull()
            assertThat(newDraft.dataModel!!.get("properties").has("name")).isTrue()
        }

        @Test
        fun `CreateContractVersion is idempotent`() {
            val first = withMediator { CreateContractVersion(templateId = templateId).execute() }
            val second = withMediator { CreateContractVersion(templateId = templateId).execute() }
            assertThat(first!!.id).isEqualTo(second!!.id)
        }
    }

    // =========================================================================
    // Scenario 7: Contract version history
    // =========================================================================

    @Nested
    inner class ContractVersionHistory {
        @Test
        fun `version history tracks all publishes`() {
            // Publish v1
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId, confirmed = true).execute() }

            // Publish v2 (add field)
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId, confirmed = true).execute() }

            val versions = withMediator { ListContractVersions(templateId = templateId).query() }
            assertThat(versions).hasSize(2)
            assertThat(versions.all { it.status == ContractVersionStatus.PUBLISHED }).isTrue()
        }
    }

    // =========================================================================
    // Scenario 8: Template version links to correct contract
    // =========================================================================

    @Nested
    inner class TemplateVersionContractLink {
        @Test
        fun `new template version links to latest contract`() {
            // Publish contract v1
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId, confirmed = true).execute() }

            // Create a new draft version
            val newVersion = withMediator { CreateVersion(defaultVariantId).execute()!! }
            assertThat(newVersion.contractVersion).isEqualTo(VersionKey.of(1))
        }

        @Test
        fun `draft version moves to new contract when editing starts`() {
            // Publish contract v1
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId, confirmed = true).execute() }

            // Start editing contract — creates draft v2
            withMediator { CreateContractVersion(templateId = templateId).execute() }

            // Template draft should now link to contract v2 (draft)
            val draft = withMediator { GetDraft(defaultVariantId).query() }
            assertThat(draft).isNotNull
            assertThat(draft!!.contractVersion).isEqualTo(VersionKey.of(2))
        }

        @Test
        fun `compatible publish upgrades all template versions to new contract`() {
            // Publish contract v1 and deploy
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            val envId = createEnvironment("production")
            val v1 = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator {
                PublishToEnvironment(
                    versionId = VersionId(v1.id, defaultVariantId),
                    environmentId = envId,
                ).execute()
            }

            // Publish compatible v2 (add optional field)
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}}}"""),
                ).execute()
            }
            val publishResult = withMediator {
                PublishContractVersion(templateId = templateId, confirmed = true).execute()
            }

            assertThat(publishResult!!.upgradedVersionCount).isGreaterThan(0)

            // All versions should now be on contract v2
            val versions = withMediator { ListVersions(defaultVariantId).query() }
            assertThat(versions).allSatisfy { v ->
                assertThat(v.contractVersion).isEqualTo(VersionKey.of(2))
            }
        }
    }
}
