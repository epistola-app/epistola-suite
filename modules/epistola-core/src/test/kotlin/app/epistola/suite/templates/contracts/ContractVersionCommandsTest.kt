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
import app.epistola.suite.templates.commands.contracts.CreateContractVersion
import app.epistola.suite.templates.commands.contracts.PublishContractVersion
import app.epistola.suite.templates.commands.contracts.UpdateContractVersion
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.model.ContractVersionStatus
import app.epistola.suite.templates.queries.contracts.GetDraftContractVersion
import app.epistola.suite.templates.queries.contracts.GetLatestContractVersion
import app.epistola.suite.templates.queries.contracts.ListContractVersions
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
        fun `publishes draft and creates next draft`() {
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
            assertThat(result!!.publishedVersion.status).isEqualTo(ContractVersionStatus.PUBLISHED)
            assertThat(result.publishedVersion.id).isEqualTo(VersionKey.of(1))
            assertThat(result.newDraft.status).isEqualTo(ContractVersionStatus.DRAFT)
            assertThat(result.newDraft.id).isEqualTo(VersionKey.of(2))
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

            // Update draft with compatible change (add optional field)
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

            // Update draft with breaking change (remove field)
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
            val draftBefore = withMediator { GetDraft(defaultVariantId).query() }
            assertThat(draftBefore!!.contractVersion).isEqualTo(VersionKey.of(1))

            // Add optional field (compatible) and publish v2
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}}}"""),
                ).execute()
            }
            val result = withMediator { PublishContractVersion(templateId = templateId).execute() }

            assertThat(result!!.compatible).isTrue()
            assertThat(result.upgradedVersionCount).isGreaterThan(0)

            // Verify template version was upgraded
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

            // Now we have: published template version on contract v1, new draft on contract v1
            // Make a breaking change and publish contract v2
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }
            val result = withMediator { PublishContractVersion(templateId = templateId).execute() }

            assertThat(result!!.compatible).isFalse()
            // Only the new draft should be upgraded, not the published version
            assertThat(result.upgradedVersionCount).isEqualTo(1) // just the new draft
        }

        @Test
        fun `publishes auto-created empty draft`() {
            // Draft always exists (auto-created with template), publish works immediately
            val result = withMediator {
                PublishContractVersion(templateId = templateId).execute()
            }
            assertThat(result).isNotNull
            assertThat(result!!.publishedVersion.dataModel).isNull() // empty contract
            assertThat(result.newDraft.id).isEqualTo(VersionKey.of(2))
        }
    }

    @Nested
    inner class PublishGuardTest {
        @Test
        fun `PublishToEnvironment rejects template version with draft contract`() {
            // Create a draft contract (not published)
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            // The default variant's draft should be linked to contract v1 (draft)
            // Create an environment and try to publish
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

            // Create a new version so it picks up the draft contract
            val version = withMediator { CreateVersion(defaultVariantId).execute()!! }

            assertThatThrownBy {
                withMediator {
                    PublishToEnvironment(
                        versionId = VersionId(version.id, defaultVariantId),
                        environmentId = EnvironmentId(env.id, tenantId),
                    ).execute()
                }
            }.hasMessageContaining("contract version")
                .hasMessageContaining("still a draft")
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
            // Now we have: v1 (published), v2 (draft)

            val versions = withMediator { ListContractVersions(templateId = templateId).query() }
            assertThat(versions).hasSize(2)
            assertThat(versions[0].id).isEqualTo(VersionKey.of(2)) // descending order
            assertThat(versions[0].status).isEqualTo(ContractVersionStatus.DRAFT)
            assertThat(versions[1].id).isEqualTo(VersionKey.of(1))
            assertThat(versions[1].status).isEqualTo(ContractVersionStatus.PUBLISHED)
        }

        @Test
        fun `GetDraftContractVersion returns draft`() {
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            val draft = withMediator { GetDraftContractVersion(templateId = templateId).query() }
            assertThat(draft).isNotNull
            assertThat(draft!!.status).isEqualTo(ContractVersionStatus.DRAFT)
        }

        @Test
        fun `GetLatestContractVersion prefers draft`() {
            withMediator {
                CreateContractVersion(templateId = templateId, dataModel = schema("""{"type":"object"}""")).execute()
            }
            withMediator { PublishContractVersion(templateId = templateId).execute() }

            val latest = withMediator { GetLatestContractVersion(templateId = templateId).query() }
            assertThat(latest).isNotNull
            assertThat(latest!!.status).isEqualTo(ContractVersionStatus.DRAFT) // draft preferred
        }
    }
}
