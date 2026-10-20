// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.model.CollectMeta
import app.epistola.api.model.GenerationResult
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.generation.collect.domain.PartitionAssignment
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.generation.collect.ndjson.NdjsonResultStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The collect stream is written by hand ([NdjsonResultStream]), not through the generated models, so
 * nothing else holds it to the contract: each line must read as the contract's `GenerationResult`,
 * and the last as its `CollectMeta`, with no member the contract does not have.
 */
class CollectWireContractTest {

    private val strict = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    @Test
    fun `a collected result line and the meta line read as the contract's models`() {
        val row = GenerationResultRow(
            sequence = 7,
            partition = 3,
            createdAt = OffsetDateTime.parse("2026-10-20T10:00:00Z"),
            requestId = GenerationRequestKey.of(UUID.randomUUID()),
            batchId = null,
            tenantKey = TenantKey.of("acme"),
            routingKey = "customer-42",
            status = ResultStatus.COMPLETED,
            documentId = DocumentKey(UUID.randomUUID()),
            correlationId = "order-1",
            templateId = TemplateKey.of("invoice"),
            variantId = VariantKey.of("default"),
            versionId = null,
            releaseVersion = "1.4.0",
            registry = "exchange.epistola.app",
            namespace = "acme",
            catalogKey = "letters",
            filename = "invoice.pdf",
            contentType = "application/pdf",
            sizeBytes = 1234,
            error = null,
            completedAt = OffsetDateTime.parse("2026-10-20T10:00:01Z"),
        )
        val out = ByteArrayOutputStream()
        NdjsonResultStream(JsonMapper.builder().build()).writeTo(
            out,
            listOf(row),
            NdjsonResultStream.MetaLine(hasMore = false, count = 1, lastSequence = 7, partitions = PartitionAssignment(64, listOf(3))),
            NdjsonResultStream.Encoding.IDENTITY,
        )
        val (resultLine, metaLine) = out.toString(Charsets.UTF_8).trimEnd('\n').split("\n")

        val result = strict.readValue(resultLine, GenerationResult::class.java)
        assertThat(result.status).isEqualTo(GenerationResult.Status.COMPLETED)
        assertThat(result.templateKey).isEqualTo("invoice")
        assertThat(result.variantKey).isEqualTo("default")
        assertThat(listOf(result.registry, result.namespace, result.catalogKey)).containsExactly("exchange.epistola.app", "acme", "letters")
        assertThat(result.releaseVersion).isEqualTo("1.4.0")

        val meta = strict.readValue(metaLine, CollectMeta::class.java)
        assertThat(meta.resultCount).isEqualTo(1)
        assertThat(meta.lastSequence).isEqualTo(7)
    }
}
