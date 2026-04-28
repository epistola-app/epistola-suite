package app.epistola.suite.templates.commands.versions

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestPublishedContractVersion
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.variants.ListVariants
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

private const val DEMO_CATALOG_URL = "classpath:demo/catalog/catalog.json"

@Timeout(30)
class PublishVersionTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()
    private lateinit var templateId: TemplateId
    private lateinit var defaultVariantId: VariantId
    private var tenantKey: TenantKey = TenantKey.of("placeholder")

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    @BeforeEach
    fun createTemplate() {
        val tenant = createTenant("PublishVersion Test")
        tenantKey = tenant.id
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        withMediator {
            CreateDocumentTemplate(id = templateId, name = "publish-version-test").execute()
        }
        defaultVariantId = VariantId(
            VariantKey.of("${templateId.key.value}-default"),
            templateId,
        )
    }

    @Nested
    inner class BasicPublish {
        @Test
        fun `publishes draft version without environment`() {
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            assertThat(draft.status).isEqualTo(VersionStatus.DRAFT)

            val result = withMediator {
                PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute()
            }

            assertThat(result).isNotNull
            assertThat(result!!.status).isEqualTo(VersionStatus.PUBLISHED)
            assertThat(result.publishedAt).isNotNull()
            assertThat(result.renderingDefaultsVersion).isNotNull()
        }

        @Test
        fun `is idempotent for already published version`() {
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            val versionId = VersionId(draft.id, defaultVariantId)

            val first = withMediator { PublishVersion(versionId = versionId).execute() }
            val second = withMediator { PublishVersion(versionId = versionId).execute() }

            assertThat(first!!.status).isEqualTo(VersionStatus.PUBLISHED)
            assertThat(second!!.status).isEqualTo(VersionStatus.PUBLISHED)
            assertThat(first.id).isEqualTo(second.id)
        }

        @Test
        fun `returns null for non-existent version`() {
            val fakeVersionId = VersionId(VersionKey.of(99), defaultVariantId)
            val result = withMediator { PublishVersion(versionId = fakeVersionId).execute() }
            assertThat(result).isNull()
        }

        @Test
        fun `returns null for archived version`() {
            // Publish then archive
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            val versionId = VersionId(draft.id, defaultVariantId)
            withMediator { PublishVersion(versionId = versionId).execute() }
            withMediator { ArchiveVersion(versionId = versionId).execute() }

            // Try to publish the archived version
            val result = withMediator { PublishVersion(versionId = versionId).execute() }
            assertThat(result).isNull()
        }
    }

    @Nested
    inner class ContractAutoPublish {
        @Test
        fun `auto-publishes compatible draft contract`() {
            // Contract v1 is a draft (auto-created). Add a schema.
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            // Publish the template version — should auto-publish the contract
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator { PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute() }

            val publishedContract = withMediator {
                GetLatestPublishedContractVersion(templateId = templateId).query()
            }
            assertThat(publishedContract).isNotNull
            assertThat(publishedContract!!.dataModel).isNotNull
        }

        @Test
        fun `blocks on breaking draft contract`() {
            // Publish a contract with a field
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}}}"""),
                ).execute()
            }
            val draft1 = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator { PublishVersion(versionId = VersionId(draft1.id, defaultVariantId)).execute() }

            // Create draft v2 with breaking change (remove field)
            withMediator { CreateContractVersion(templateId = templateId).execute() }
            withMediator {
                UpdateContractVersion(
                    templateId = templateId,
                    dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""),
                ).execute()
            }

            // Create a new template version draft
            val newDraft = withMediator { CreateVersion(defaultVariantId).execute()!! }

            // Publish should be blocked
            assertThatThrownBy {
                withMediator {
                    PublishVersion(versionId = VersionId(newDraft.id, defaultVariantId)).execute()
                }
            }.hasMessageContaining("breaking changes")
        }
    }

    @Nested
    inner class NoAutoCreateDraft {
        @Test
        fun `does not auto-create a next draft after publish`() {
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            withMediator { PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute() }

            // No draft should exist after publish (on-demand lifecycle)
            val nextDraft = withMediator { GetDraft(defaultVariantId).query() }
            assertThat(nextDraft).isNull()
        }
    }

    @Nested
    inner class SubscribedCatalog {
        @Test
        fun `idempotent for already-published version in subscribed catalog`() {
            val tenant = createTenant("Subscribed PublishVersion Test")
            val tenantId = TenantId(tenant.id)
            val catalogKey = CatalogKey.of("epistola-demo")

            withMediator {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
                InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()

                val catalogId = CatalogId(catalogKey, tenantId)
                val subscribedTemplateId = TemplateId(TemplateKey.of("hello-world"), catalogId)
                val variants = ListVariants(templateId = subscribedTemplateId).query()
                val variant = variants.first()
                val variantId = VariantId(variant.id, subscribedTemplateId)

                val versions = ListVersions(variantId = variantId).query()
                val published = versions.first()
                assertThat(published.status).isEqualTo(VersionStatus.PUBLISHED)

                // Should succeed without throwing CatalogReadOnlyException
                val result = PublishVersion(versionId = VersionId(published.id, variantId)).execute()
                assertThat(result).isNotNull
                assertThat(result!!.status).isEqualTo(VersionStatus.PUBLISHED)
            }
        }

        @Test
        fun `blocks publishing draft in subscribed catalog`() {
            val tenant = createTenant("Subscribed Draft Block Test")
            val tenantId = TenantId(tenant.id)
            val catalogKey = CatalogKey.of("epistola-demo")

            withMediator {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
                InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()

                val catalogId = CatalogId(catalogKey, tenantId)
                val subscribedTemplateId = TemplateId(TemplateKey.of("hello-world"), catalogId)
                val variants = ListVariants(templateId = subscribedTemplateId).query()
                val variant = variants.first()
                val variantId = VariantId(variant.id, subscribedTemplateId)

                // Create a draft version using import context (simulating an inconsistent state)
                val draftVersion = CatalogImportContext.runAsImport {
                    CreateVersion(variantId).execute()
                }

                // Should throw because we can't mutate a draft in a subscribed catalog
                assertThatThrownBy {
                    PublishVersion(versionId = VersionId(draftVersion!!.id, variantId)).execute()
                }.isInstanceOf(CatalogReadOnlyException::class.java)
                    .hasMessageContaining("read-only")
            }
        }
    }
}
