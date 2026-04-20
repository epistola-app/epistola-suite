package app.epistola.suite.templates.validation

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class TemplateRecentUsageCompatibilityServiceTest : IntegrationTestBase() {
    @Autowired
    private lateinit var service: TemplateRecentUsageCompatibilityService

    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()

    @Test
    @DisplayName("analyze returns compatible with zero checked when no recent samples exist")
    fun analyzeReturnsCompatibleWhenNoRecentSamplesExist() {
        withMediator {
            val tenant = createTenant("No Samples")
            val prepared = prepareTemplateForGeneration(tenant.id)

            val result = service.analyze(
                tenantKey = tenant.id,
                templateKey = prepared.templateId.key,
                schema = schemaWithString("name"),
            )

            assertThat(result.compatible).isTrue()
            assertThat(result.available).isTrue()
            assertThat(result.summary.checkedCount).isEqualTo(0)
            assertThat(result.summary.incompatibleCount).isEqualTo(0)
            assertThat(result.samples).isEmpty()
            assertThat(result.issues).isEmpty()
        }
    }

    @Test
    @DisplayName("analyze returns incompatible when sampled request data violates schema")
    fun analyzeReturnsIncompatibleWhenRequestDataViolatesSchema() {
        withMediator {
            val tenant = createTenant("Incompatible Samples")
            val prepared = prepareTemplateForGeneration(tenant.id)

            GenerateDocument(
                tenantId = tenant.id,
                templateId = prepared.templateId.key,
                variantId = prepared.variantId.key,
                versionId = prepared.versionId,
                environmentId = null,
                data = objectMapper.createObjectNode().put("count", "not-a-number"),
                filename = "sample.pdf",
            ).execute().also { request ->
                jdbi.withHandle<Any, Exception> { handle ->
                    handle.createUpdate(
                        """
                        UPDATE document_generation_requests
                        SET status = :status
                        WHERE id = :id
                        """.trimIndent(),
                    )
                        .bind("status", RequestStatus.FAILED.name)
                        .bind("id", request.id)
                        .execute()
                    Unit
                }
            }

            val result = service.analyze(
                tenantKey = tenant.id,
                templateKey = prepared.templateId.key,
                schema = schemaWithInteger("count"),
            )

            assertThat(result.available).isTrue()
            assertThat(result.compatible).isFalse()
            assertThat(result.summary.checkedCount).isEqualTo(1)
            assertThat(result.summary.compatibleCount).isEqualTo(0)
            assertThat(result.summary.incompatibleCount).isEqualTo(1)
            val sample = result.samples.single()
            assertThat(sample.compatible).isFalse()
            assertThat(sample.errorCount).isEqualTo(1)
            assertThat(result.issues).hasSize(1)
            assertThat(result.issues.first().status).isEqualTo(RequestStatus.FAILED)
            assertThat(result.issues.first().errors).isNotEmpty()
        }
    }

    @Test
    @DisplayName("analyze reports type mismatch instead of missing field when parent value changed type")
    fun analyzeReportsTypeMismatchWhenParentValueChangedType() {
        withMediator {
            val tenant = createTenant("Type Changed Samples")
            val prepared = prepareTemplateForGeneration(tenant.id)

            GenerateDocument(
                tenantId = tenant.id,
                templateId = prepared.templateId.key,
                variantId = prepared.variantId.key,
                versionId = prepared.versionId,
                environmentId = null,
                data = objectMapper.createObjectNode().put("customer", "legacy-value"),
                filename = "sample.pdf",
            ).execute().also { request ->
                jdbi.withHandle<Any, Exception> { handle ->
                    handle.createUpdate(
                        """
                        UPDATE document_generation_requests
                        SET status = :status
                        WHERE id = :id
                        """.trimIndent(),
                    )
                        .bind("status", RequestStatus.FAILED.name)
                        .bind("id", request.id)
                        .execute()
                    Unit
                }
            }

            val schema = objectMapper.createObjectNode()
                .put("type", "object")
                .set(
                    "properties",
                    objectMapper.createObjectNode().set(
                        "customer",
                        objectMapper.createObjectNode()
                            .put("type", "object")
                            .set(
                                "properties",
                                objectMapper.createObjectNode().set(
                                    "name",
                                    objectMapper.createObjectNode().put("type", "string"),
                                ),
                            )
                            .set("required", objectMapper.createArrayNode().add("name")),
                    ),
                )

            val result = service.analyze(
                tenantKey = tenant.id,
                templateKey = prepared.templateId.key,
                schema = schema,
            )

            assertThat(result.compatible).isFalse()
            assertThat(result.issues).hasSize(1)
            val error = result.issues.first().errors.single()
            assertThat(error.path).isEqualTo("/customer")
            assertThat(error.message).isEqualTo("expected object but found string")
            Unit
        }
    }

    @Test
    @DisplayName("analyze ignores requests with statuses outside configured filter")
    fun analyzeIgnoresRequestsOutsideConfiguredStatuses() {
        withMediator {
            val tenant = createTenant("Filtered Status")
            val prepared = prepareTemplateForGeneration(tenant.id)

            val request = GenerateDocument(
                tenantId = tenant.id,
                templateId = prepared.templateId.key,
                variantId = prepared.variantId.key,
                versionId = prepared.versionId,
                environmentId = null,
                data = objectMapper.createObjectNode().put("name", "kept"),
                filename = "sample.pdf",
            ).execute()

            jdbi.withHandle<Any, Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE document_generation_requests
                    SET status = :status
                    WHERE id = :id
                    """.trimIndent(),
                )
                    .bind("status", RequestStatus.CANCELLED.name)
                    .bind("id", request.id)
                    .execute()
                Unit
            }

            val result = service.analyze(
                tenantKey = tenant.id,
                templateKey = prepared.templateId.key,
                schema = schemaWithString("name"),
            )

            assertThat(result.available).isTrue()
            assertThat(result.compatible).isTrue()
            assertThat(result.summary.checkedCount).isEqualTo(0)
            assertThat(result.issues).isEmpty()
        }
    }

    @Test
    @DisplayName("analyze returns unavailable when sample row cannot be parsed as object data")
    fun analyzeReturnsUnavailableWhenRowCannotBeParsed() {
        withMediator {
            val tenant = createTenant("Malformed Sample")
            val prepared = prepareTemplateForGeneration(tenant.id)

            val request = GenerateDocument(
                tenantId = tenant.id,
                templateId = prepared.templateId.key,
                variantId = prepared.variantId.key,
                versionId = prepared.versionId,
                environmentId = null,
                data = objectMapper.createObjectNode().put("name", "ok"),
                filename = "sample.pdf",
            ).execute()

            jdbi.withHandle<Any, Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE document_generation_requests
                    SET data = '"bad-shape"'::jsonb,
                        status = :status
                    WHERE id = :id
                    """.trimIndent(),
                )
                    .bind("status", RequestStatus.FAILED.name)
                    .bind("id", request.id)
                    .execute()
                Unit
            }

            val result = service.analyze(
                tenantKey = tenant.id,
                templateKey = prepared.templateId.key,
                schema = schemaWithString("name"),
            )

            assertThat(result.available).isFalse()
            assertThat(result.compatible).isFalse()
            assertThat(result.summary.checkedCount).isEqualTo(0)
            assertThat(result.summary.incompatibleCount).isEqualTo(0)
            assertThat(result.unavailableReason).isEqualTo(
                "Recent usage compatibility check is temporarily unavailable.",
            )
        }
    }

    @Test
    @DisplayName("analyze excludes samples outside configured recent usage window")
    fun analyzeExcludesSamplesOutsideRecentWindow() {
        withMediator {
            val tenant = createTenant("Old Samples")
            val prepared = prepareTemplateForGeneration(tenant.id)

            val request = GenerateDocument(
                tenantId = tenant.id,
                templateId = prepared.templateId.key,
                variantId = prepared.variantId.key,
                versionId = prepared.versionId,
                environmentId = null,
                data = objectMapper.createObjectNode().put("count", "not-a-number"),
                filename = "sample.pdf",
            ).execute()

            jdbi.withHandle<Any, Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE document_generation_requests
                    SET created_at = NOW() - INTERVAL '10 days',
                        status = :status
                    WHERE id = :id
                    """.trimIndent(),
                )
                    .bind("status", RequestStatus.FAILED.name)
                    .bind("id", request.id)
                    .execute()
                Unit
            }

            val result = service.analyze(
                tenantKey = tenant.id,
                templateKey = prepared.templateId.key,
                schema = schemaWithInteger("count"),
            )

            assertThat(result.available).isTrue()
            assertThat(result.compatible).isTrue()
            assertThat(result.summary.checkedCount).isEqualTo(0)
            assertThat(result.issues).isEmpty()
        }
    }

    @Test
    @DisplayName("fetchRecentSamples parse failure includes requestId context")
    fun fetchRecentSamplesParseFailureIncludesRequestId() {
        withMediator {
            val tenant = createTenant("Parse Context")
            val prepared = prepareTemplateForGeneration(tenant.id)

            val request = GenerateDocument(
                tenantId = tenant.id,
                templateId = prepared.templateId.key,
                variantId = prepared.variantId.key,
                versionId = prepared.versionId,
                environmentId = null,
                data = objectMapper.createObjectNode().put("name", "ok"),
                filename = "sample.pdf",
            ).execute()

            jdbi.withHandle<Any, Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE document_generation_requests
                    SET data = '"bad-shape"'::jsonb,
                        status = :status
                    WHERE id = :id
                    """.trimIndent(),
                )
                    .bind("status", RequestStatus.FAILED.name)
                    .bind("id", request.id)
                    .execute()
                Unit
            }

            val method = TemplateRecentUsageCompatibilityService::class.java
                .declaredMethods
                .first { it.name.startsWith("fetchRecentSamples-") && it.parameterCount == 2 }
            method.isAccessible = true

            val exception = runCatching {
                method.invoke(service, tenant.id.value, prepared.templateId.key.value)
            }.exceptionOrNull()

            val root = exception?.cause ?: exception
            assertThat(root).isInstanceOf(IllegalStateException::class.java)
            assertThat(root!!.message).contains("Failed to parse recent usage sample requestId=${request.id}")
        }
    }

    private fun prepareTemplateForGeneration(tenantKey: TenantKey): PreparedTemplate {
        val tenantId = TenantId(tenantKey)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))

        CreateDocumentTemplate(id = templateId, name = "Recent Usage Template").execute()

        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        CreateVariant(
            id = variantId,
            title = "Default",
            description = null,
            attributes = emptyMap(),
        ).execute()

        val draft = UpdateDraft(
            variantId = variantId,
            templateModel = TestTemplateBuilder.buildMinimal(name = "Recent Usage Template"),
        ).execute()!!

        return PreparedTemplate(
            templateId = templateId,
            variantId = variantId,
            versionId = draft.id,
        )
    }

    private fun schemaWithString(field: String): ObjectNode = objectMapper.createObjectNode()
        .put("type", "object")
        .set(
            "properties",
            objectMapper.createObjectNode().set(field, objectMapper.createObjectNode().put("type", "string")),
        )

    private fun schemaWithInteger(field: String): ObjectNode = objectMapper.createObjectNode()
        .put("type", "object")
        .set(
            "properties",
            objectMapper.createObjectNode().set(field, objectMapper.createObjectNode().put("type", "integer")),
        )

    private data class PreparedTemplate(
        val templateId: TemplateId,
        val variantId: VariantId,
        val versionId: app.epistola.suite.common.ids.VersionKey,
    )
}
