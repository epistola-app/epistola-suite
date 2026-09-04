// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.versions

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestPublishedContractVersion
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

@Timeout(30)
class PublishVersionTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

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
            CreateDocumentTemplate(id = templateId, name = "publish-version-test").execute().withRequiredDataExample()
        }
        defaultVariantId = VariantId(
            VariantKey.INITIAL,
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

        @Test
        fun `rejects an invalid legacy draft at the publish boundary`() {
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            jdbi.withHandle<Int, Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE template_versions
                    SET template_model = jsonb_set(template_model, '{root}', '"missing-root"'::jsonb)
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                      AND template_key = :templateKey AND variant_key = :variantKey AND id = :versionId
                    """,
                )
                    .bind("tenantKey", templateId.tenantKey)
                    .bind("catalogKey", templateId.catalogKey)
                    .bind("templateKey", templateId.key)
                    .bind("variantKey", defaultVariantId.key)
                    .bind("versionId", draft.id)
                    .execute()
            }

            assertThatThrownBy {
                withMediator {
                    PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute()
                }
            }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.field).isEqualTo("templateModel.root")
                }

            val stillDraft = withMediator { GetDraft(defaultVariantId).query() }
            assertThat(stillDraft).isNotNull
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
        fun `publishes after a concurrent transaction publishes the linked contract`() {
            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            val versionId = VersionId(draft.id, defaultVariantId)
            val publishTask = withMediator {
                MediatorContext.callable(mediator) {
                    PublishVersion(versionId = versionId).execute()
                }
            }
            val blocker = jdbi.open()
            val executor = Executors.newSingleThreadExecutor()

            try {
                blocker.begin()
                val blockerPid = blocker.createQuery("SELECT pg_backend_pid()")
                    .mapTo(Int::class.java)
                    .one()
                val updated = blocker.createUpdate(
                    """
                    UPDATE contract_versions
                    SET status = 'published', published_at = NOW()
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                      AND template_key = :templateKey AND id = :contractVersion
                    """,
                )
                    .bind("tenantKey", templateId.tenantKey)
                    .bind("catalogKey", templateId.catalogKey)
                    .bind("templateKey", templateId.key)
                    .bind("contractVersion", draft.contractVersion)
                    .execute()
                assertThat(updated).isEqualTo(1)

                val publishFuture = executor.submit(publishTask)
                await()
                    .atMost(Duration.ofSeconds(5))
                    .until {
                        jdbi.withHandle<Boolean, Exception> { observer ->
                            observer.createQuery(
                                """
                                SELECT EXISTS (
                                    SELECT 1
                                    FROM pg_stat_activity
                                    WHERE datname = current_database()
                                      AND :blockerPid = ANY(pg_blocking_pids(pid))
                                )
                                """,
                            )
                                .bind("blockerPid", blockerPid)
                                .mapTo(Boolean::class.java)
                                .one()
                        }
                    }

                blocker.commit()

                val published = publishFuture.get(5, TimeUnit.SECONDS)
                assertThat(published).isNotNull
                assertThat(published!!.status).isEqualTo(VersionStatus.PUBLISHED)
            } finally {
                if (blocker.isInTransaction) {
                    blocker.rollback()
                }
                blocker.close()
                executor.shutdownNow()
            }
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
    inner class StencilValidation {
        @Test
        fun `blocks publish when referenced stencil has no published version`() {
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, templateId.catalogId)
            withMediator {
                CreateStencil(id = stencilId, name = "Draft-Only Stencil").execute()
                UpdateDraft(
                    variantId = defaultVariantId,
                    templateModel = templateModelReferencingStencil(stencilKey, draftVersion = 1),
                ).execute()
            }

            val draft = withMediator { GetDraft(defaultVariantId).query()!! }

            assertThatThrownBy {
                withMediator {
                    PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute()
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("cannot reference draft stencil versions")
        }

        @Test
        fun `blocks publish when pinned stencil version is still draft`() {
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, templateId.catalogId)
            withMediator {
                CreateStencil(id = stencilId, name = "Pinned Stencil").execute()
                PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
                CreateStencilVersion(stencilId = stencilId).execute()
                UpdateDraft(
                    variantId = defaultVariantId,
                    templateModel = templateModelReferencingStencil(stencilKey, version = 2, draftVersion = 2),
                ).execute()
            }

            val draft = withMediator { GetDraft(defaultVariantId).query()!! }

            assertThatThrownBy {
                withMediator {
                    PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute()
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("cannot reference draft stencil versions")
        }

        @Test
        fun `allows publish when referenced stencil is published`() {
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, templateId.catalogId)
            withMediator {
                CreateStencil(id = stencilId, name = "Published Stencil").execute()
                PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
                UpdateDraft(
                    variantId = defaultVariantId,
                    templateModel = templateModelReferencingStencil(stencilKey),
                ).execute()
            }

            val draft = withMediator { GetDraft(defaultVariantId).query()!! }

            val result = withMediator {
                PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute()
            }
            assertThat(result).isNotNull
            assertThat(result!!.status).isEqualTo(VersionStatus.PUBLISHED)
        }

        @Test
        fun `allows incomplete required bindings in draft but blocks publication`() {
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, templateId.catalogId)
            withMediator {
                CreateStencil(id = stencilId, name = "Parameterized Stencil").execute()
                PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
                UpdateDraft(
                    variantId = defaultVariantId,
                    templateModel = templateModelReferencingStencil(
                        stencilKey = stencilKey,
                        requiredParameter = "recipientName",
                    ),
                ).execute()
            }

            val draft = withMediator { GetDraft(defaultVariantId).query()!! }
            assertThat(draft.status).isEqualTo(VersionStatus.DRAFT)

            assertThatThrownBy {
                withMediator {
                    PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute()
                }
            }.isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.NODE_PARAMETER_BINDING_MISSING_REQUIRED)
                .hasMessageContaining("recipientName")
        }
    }

    private fun templateModelReferencingStencil(
        stencilKey: StencilKey,
        version: Int = 1,
        draftVersion: Int? = null,
        requiredParameter: String? = null,
    ): TemplateDocument {
        val rootId = "root-1"
        val slotId = "slot-1"
        val stencilNodeId = "stencil-1"
        val props = mutableMapOf<String, Any?>(
            "stencilId" to stencilKey.value,
            "version" to version,
        )
        draftVersion?.let { props["draftVersion"] = it }
        if (requiredParameter != null) {
            props["parameterSchemaSnapshot"] = mapOf(
                "type" to "object",
                "properties" to mapOf(requiredParameter to mapOf("type" to "string")),
                "required" to listOf(requiredParameter),
            )
        }
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(
                rootId to Node(id = rootId, type = "root", slots = listOf(slotId)),
                stencilNodeId to Node(id = stencilNodeId, type = "stencil", props = props),
            ),
            slots = mapOf(
                slotId to Slot(id = slotId, nodeId = rootId, name = "children", children = listOf(stencilNodeId)),
            ),
            themeRef = ThemeRef.Inherit,
        )
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
