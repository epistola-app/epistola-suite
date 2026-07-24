// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.node.JsonNodeFactory
import java.time.OffsetDateTime
import java.util.UUID

class EmitGenerationResultHandlerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `emits a row at the partition computed from routingKey`() {
        val tenant = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
        val req = sampleRequest(tenant, routingKey = "order-7890")
        val expectedPartition = Partition.partitionFor("order-7890")

        val row = withMediator {
            EmitGenerationResult(
                request = req,
                status = ResultStatus.COMPLETED,
                documentId = DocumentKey.of(UUID.randomUUID()),
                sizeBytes = 1024L,
                contentType = "application/pdf",
                error = null,
                completedAt = OffsetDateTime.now(),
            ).execute()
        }

        assertThat(row.partition).isEqualTo(expectedPartition)
        assertThat(row.routingKey).isEqualTo("order-7890")
        assertThat(row.tenantKey).isEqualTo(tenant)
        assertThat(row.status).isEqualTo(ResultStatus.COMPLETED)
        assertThat(row.sequence).isPositive
        assertThat(row.documentId).isNotNull
    }

    @Test
    fun `falls back to requestId when routingKey is null`() {
        val tenant = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
        val req = sampleRequest(tenant, routingKey = null)
        val expectedPartition = Partition.partitionFor(req.id.value.toString())

        val row = withMediator {
            EmitGenerationResult(
                request = req,
                status = ResultStatus.COMPLETED,
                documentId = DocumentKey.of(UUID.randomUUID()),
                sizeBytes = 1L,
                contentType = "application/pdf",
                error = null,
                completedAt = OffsetDateTime.now(),
            ).execute()
        }

        assertThat(row.partition).isEqualTo(expectedPartition)
        assertThat(row.routingKey).isEqualTo(req.id.value.toString())
    }

    @Test
    fun `failed status persists error and leaves documentId null`() {
        val req = sampleRequest(TenantKey.of("t-${UUID.randomUUID().toString().take(8)}"), routingKey = "fail-key")

        val row = withMediator {
            EmitGenerationResult(
                request = req,
                status = ResultStatus.FAILED,
                documentId = null,
                sizeBytes = null,
                contentType = null,
                error = "template render exploded",
                completedAt = OffsetDateTime.now(),
            ).execute()
        }

        assertThat(row.status).isEqualTo(ResultStatus.FAILED)
        assertThat(row.documentId).isNull()
        assertThat(row.error).isEqualTo("template render exploded")
    }

    @Test
    fun `rejects COMPLETED without documentId`() {
        val req = sampleRequest(TenantKey.of("test-tenant"), routingKey = "x")
        assertThatThrownBy {
            EmitGenerationResult(
                request = req,
                status = ResultStatus.COMPLETED,
                documentId = null,
                sizeBytes = null,
                contentType = null,
                error = null,
                completedAt = OffsetDateTime.now(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects FAILED without error`() {
        val req = sampleRequest(TenantKey.of("test-tenant"), routingKey = "x")
        assertThatThrownBy {
            EmitGenerationResult(
                request = req,
                status = ResultStatus.FAILED,
                documentId = null,
                sizeBytes = null,
                contentType = null,
                error = null,
                completedAt = OffsetDateTime.now(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `emitted row is persisted to generation_results at the same partition`() {
        // Sanity check that the round-trip through emit + storage is consistent.
        val tenant = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
        val req = sampleRequest(tenant, routingKey = "round-trip-key")
        val partition = Partition.partitionFor("round-trip-key")

        val emitted = withMediator {
            EmitGenerationResult(
                request = req,
                status = ResultStatus.COMPLETED,
                documentId = DocumentKey.of(UUID.randomUUID()),
                sizeBytes = 1L,
                contentType = "application/pdf",
                error = null,
                completedAt = OffsetDateTime.now(),
            ).execute()
        }

        val rowCount = jdbi.withHandle<Int, Exception> { h ->
            h.createQuery(
                """
                SELECT COUNT(*) FROM generation_results
                WHERE tenant_key = :tenant AND partition = :partition AND sequence = :seq
                """,
            )
                .bind("tenant", tenant)
                .bind("partition", partition)
                .bind("seq", emitted.sequence)
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(rowCount).isEqualTo(1)
    }

    private fun sampleRequest(
        tenantKey: TenantKey,
        routingKey: String?,
    ) = DocumentGenerationRequest(
        id = GenerationRequestKey.of(UUID.randomUUID()),
        batchId = BatchKey.of(UUID.randomUUID()),
        tenantKey = tenantKey,
        catalogKey = CatalogKey.DEFAULT,
        templateKey = TemplateKey.of("invoice"),
        variantKey = VariantKey.of("default"),
        versionKey = null,
        environmentKey = EnvironmentKey.of("production"),
        data = JsonNodeFactory.instance.objectNode(),
        filename = "doc.pdf",
        correlationId = "cor-1",
        routingKey = routingKey,
        documentKey = null,
        status = RequestStatus.IN_PROGRESS,
        claimedBy = "test-instance",
        claimedAt = OffsetDateTime.now(),
        errorMessage = null,
        createdAt = OffsetDateTime.now(),
        startedAt = OffsetDateTime.now(),
        completedAt = null,
        expiresAt = null,
    )
}
