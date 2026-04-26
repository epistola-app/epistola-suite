package app.epistola.suite.templates.contracts

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
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
import app.epistola.suite.templates.contracts.queries.GetDraftContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.contracts.queries.ListContractVersions
import app.epistola.suite.templates.queries.versions.GetDraft
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

@Timeout(30)
class ContractVersionCommandsTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()

    private lateinit var templateId: TemplateId
    private var tenantKey: TenantKey = TenantKey.of("placeholder")

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    @BeforeEach
    fun createTemplate() {
        val tenant = createTenant("Contract Test Tenant")
        tenantKey = tenant.id
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        withMediator {
            CreateDocumentTemplate(id = templateId, name = "contract-test-template").execute()
        }
    }

    @Nested
    inner class CreateContractVersionTest {
        @Test
        fun `template creation auto-creates draft contract v1`() {
            val draft = withMediator {
                GetDraftContractVersion(templateId = templateId).query()
            }

            assertThat(draft).isNotNull
            assertThat(draft!!.id).isEqualTo(VersionKey.of(1))
            assertThat(draft.status).isEqualTo(ContractVersionStatus.DRAFT)
            assertThat(draft.dataModel).isNull() // empty contract
        }

        @Test
        fun `CreateContractVersion is idempotent - returns existing draft`() {
            val first = withMediator {
                CreateContractVersion(templateId = templateId, dataModel = schema("""{"type":"object"}""")).execute()
            }
            val second = withMediator {
                CreateContractVersion(templateId = templateId, dataModel = schema("""{"type":"object","properties":{"x":{"type":"string"}}}""")).execute()
            }

            assertThat(first!!.id).isEqualTo(second!!.id)
        }

        @Test
        fun `template version draft links to contract v1`() {
            val defaultVariantId = VariantId(
                VariantKey.of("${templateId.key.value}-default"),
                templateId,
            )
            val draft = withMediator { GetDraft(defaultVariantId).query() }
            assertThat(draft).isNotNull
            assertThat(draft!!.contractVersion).isEqualTo(VersionKey.of(1))
        }

        @Test
        fun `returns null for non-existent template`() {
            val fakeId = TemplateId(
                app.epistola.suite.common.ids.TemplateKey.of("non-existent-tmpl"),
                templateId.catalogId,
            )
            val result = withMediator {
                CreateContractVersion(templateId = fakeId).execute()
            }
            assertThat(result).isNull()
        }
    }

    @Nested
    inner class UpdateContractVersionTest {
        @Test
        fun `updates draft contract schema`() {
            withMediator { CreateContractVersion(templateId = templateId).execute() }

            val result = withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            assertThat(result).isNotNull
            assertThat(result!!.contractVersion.dataModel).isNotNull
        }

        @Test
        fun `updates auto-created draft`() {
            // Draft always exists (auto-created with template), so update works immediately
            val result = withMediator {
                UpdateContractVersion(templateId = templateId, dataModel = schema("""{"type":"object"}""")).execute()
            }
            assertThat(result).isNotNull
            assertThat(result!!.contractVersion.dataModel).isNotNull
        }
    }

    @Nested
    inner class PublishContractVersionTest {
        @Test
        fun `publishes draft`() {
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            val result = withMediator {
                PublishContractVersion(templateId = templateId).execute()
            }

            assertThat(result).isNotNull
            assertThat(result!!.publishedVersion!!.status).isEqualTo(ContractVersionStatus.PUBLISHED)
            assertThat(result.publishedVersion.id).isEqualTo(VersionKey.of(1))
            assertThat(result.compatible).isTrue() // first version, no previous to compare
        }

        @Test
        fun `detects backwards compatible change`() {
            // Publish first version
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId).execute() }

            // Create a new draft on-demand, then update with compatible change (add optional field)
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}"""),
                ).execute()
            }

            val result = withMediator { PublishContractVersion(templateId = templateId).execute() }

            assertThat(result!!.compatible).isTrue()
            assertThat(result.breakingChanges).isEmpty()
        }

        @Test
        fun `detects breaking change - field removed`() {
            // Publish first version with two fields
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId).execute() }

            // Create a new draft on-demand, then update with breaking change (remove field)
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            val result = withMediator { PublishContractVersion(templateId = templateId).execute() }

            assertThat(result!!.compatible).isFalse()
            assertThat(result.breakingChanges).hasSize(1)
            assertThat(result.breakingChanges[0].path).isEqualTo("age")
        }

        @Test
        fun `auto-upgrades all template versions on compatible change`() {
            // Create contract v1 and publish
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId).execute() }

            // Get the default variant's draft and check its contract version
            val defaultVariantId = VariantId(
                VariantKey.of("${templateId.key.value}-default"),
                templateId,
            )
            // Template version draft still points to contract v1 (published)
            val draftBefore = withMediator { GetDraft(defaultVariantId).query() }
            assertThat(draftBefore!!.contractVersion).isEqualTo(VersionKey.of(1))

            // Create a new contract draft on-demand — this links draft template versions to v2
            withMediator { CreateContractVersion(templateId = templateId).execute() }

            // Draft template version should now point to contract v2 (draft)
            val draftAfterCreate = withMediator { GetDraft(defaultVariantId).query() }
            assertThat(draftAfterCreate!!.contractVersion).isEqualTo(VersionKey.of(2))

            // Update and publish contract v2
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}}}"""),
                ).execute()
            }
            val result = withMediator { PublishContractVersion(templateId = templateId).execute() }

            assertThat(result!!.compatible).isTrue()
            // upgradedVersionCount is 0 because CreateContractVersion already linked drafts to v2
            assertThat(result.upgradedVersionCount).isEqualTo(0)

            // Verify template version still points to v2 (now published)
            val draftAfter = withMediator { GetDraft(defaultVariantId).query() }
            assertThat(draftAfter!!.contractVersion).isEqualTo(VersionKey.of(2))
        }

        @Test
        fun `breaking change only upgrades draft template versions`() {
            val defaultVariantId = VariantId(
                VariantKey.of("${templateId.key.value}-default"),
                templateId,
            )

            // Update and publish contract v1
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}"""),
                ).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId).execute() }

            // Publish template version to an environment to make it published
            val tenantId = TenantId(tenantKey)
            val env = withMediator {
                CreateEnvironment(
                    id = EnvironmentId(EnvironmentKey.of("test-env"), tenantId),
                    name = "test-env",
                ).execute()
            }
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator {
                PublishToEnvironment(
                    versionId = VersionId(draft.id, defaultVariantId),
                    environmentId = EnvironmentId(env.id, tenantId),
                ).execute()
            }

            // Create a new draft template version on-demand (no auto-creation after publish)
            val newDraft = withMediator { CreateVersion(defaultVariantId).execute()!! }

            // Now we have: published template version on contract v1, new draft on contract v1
            // Create a new contract draft on-demand — this links the draft template version to v2
            withMediator { CreateContractVersion(templateId = templateId).execute() }

            // Make a breaking change and publish contract v2
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            val result = withMediator { PublishContractVersion(templateId = templateId).execute() }

            assertThat(result!!.compatible).isFalse()
            // 0 upgrades because CreateContractVersion already linked draft template versions to v2;
            // the published template version stays on v1 (breaking change = don't upgrade non-drafts)
            assertThat(result.upgradedVersionCount).isEqualTo(0)
        }

        @Test
        fun `publishes auto-created empty draft`() {
            // Draft always exists (auto-created with template), publish works immediately
            val result = withMediator {
                PublishContractVersion(templateId = templateId).execute()
            }
            assertThat(result).isNotNull
            assertThat(result!!.publishedVersion!!.dataModel).isNull() // empty contract
        }
    }

    @Nested
    inner class PublishGuardTest {
        @Test
        fun `PublishToEnvironment rejects draft contract`() {
            // Create a draft contract (not published)
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            val tenantId = TenantId(tenantKey)
            val env = withMediator {
                CreateEnvironment(
                    id = EnvironmentId(EnvironmentKey.of("guard-test-env"), tenantId),
                    name = "guard-test-env",
                ).execute()
            }

            val defaultVariantId = VariantId(
                VariantKey.of("${templateId.key.value}-default"),
                templateId,
            )

            val version = withMediator { CreateVersion(defaultVariantId).execute()!! }

            // Guard rejects because the contract is still a draft
            assertThatThrownBy {
                withMediator {
                    PublishToEnvironment(
                        versionId = VersionId(version.id, defaultVariantId),
                        environmentId = EnvironmentId(env.id, tenantId),
                    ).execute()
                }
            }.hasMessageContaining("still a draft")
        }
    }

    @Nested
    inner class QueryTests {
        @Test
        fun `ListContractVersions returns all versions`() {
            withMediator {
                CreateContractVersion(templateId = templateId, dataModel = schema("""{"type":"object"}""")).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId).execute() }
            // Now we have: v1 (published) — no auto-created draft

            val versions = withMediator { ListContractVersions(templateId = templateId).query() }
            assertThat(versions).hasSize(1)
            assertThat(versions[0].id).isEqualTo(VersionKey.of(1))
            assertThat(versions[0].status).isEqualTo(ContractVersionStatus.PUBLISHED)
        }

        @Test
        fun `GetDraftContractVersion returns draft`() {
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            val draft = withMediator { GetDraftContractVersion(templateId = templateId).query() }
            assertThat(draft).isNotNull
            assertThat(draft!!.status).isEqualTo(ContractVersionStatus.DRAFT)
        }

        @Test
        fun `GetLatestContractVersion returns published when no draft exists`() {
            withMediator {
                CreateContractVersion(templateId = templateId, dataModel = schema("""{"type":"object"}""")).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId).execute() }

            val latest = withMediator { GetLatestContractVersion(templateId = templateId).query() }
            assertThat(latest).isNotNull
            assertThat(latest!!.status).isEqualTo(ContractVersionStatus.PUBLISHED)
        }

        @Test
        fun `GetLatestContractVersion prefers draft when it exists`() {
            // Publish v1
            withMediator { PublishContractVersion(templateId = templateId).execute() }
            // Create a new draft (v2) on-demand
            withMediator { CreateContractVersion(templateId = templateId).execute() }

            val latest = withMediator { GetLatestContractVersion(templateId = templateId).query() }
            assertThat(latest).isNotNull
            assertThat(latest!!.status).isEqualTo(ContractVersionStatus.DRAFT)
        }
    }
}
