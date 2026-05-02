package app.epistola.suite.generation.collect.ndjson

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.generation.collect.domain.PartitionAssignment
import app.epistola.suite.generation.collect.domain.ResultStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.GZIPInputStream

class NdjsonResultStreamTest {

    private val mapper = ObjectMapper()
    private val stream = NdjsonResultStream(mapper)

    // ----- encoding negotiation -----

    @Test
    fun `negotiateEncoding defaults to gzip when header is null`() {
        assertThat(stream.negotiateEncoding(null)).isEqualTo(NdjsonResultStream.Encoding.GZIP)
    }

    @Test
    fun `negotiateEncoding defaults to gzip when header is blank`() {
        assertThat(stream.negotiateEncoding("")).isEqualTo(NdjsonResultStream.Encoding.GZIP)
        assertThat(stream.negotiateEncoding("   ")).isEqualTo(NdjsonResultStream.Encoding.GZIP)
    }

    @Test
    fun `negotiateEncoding picks gzip when client lists it`() {
        assertThat(stream.negotiateEncoding("gzip")).isEqualTo(NdjsonResultStream.Encoding.GZIP)
        assertThat(stream.negotiateEncoding("identity, gzip")).isEqualTo(NdjsonResultStream.Encoding.GZIP)
        assertThat(stream.negotiateEncoding("br, gzip;q=0.9")).isEqualTo(NdjsonResultStream.Encoding.GZIP)
    }

    @Test
    fun `negotiateEncoding picks gzip when client accepts everything`() {
        assertThat(stream.negotiateEncoding("*")).isEqualTo(NdjsonResultStream.Encoding.GZIP)
    }

    @Test
    fun `negotiateEncoding falls back to identity when client lists only unsupported encodings`() {
        assertThat(stream.negotiateEncoding("br, deflate")).isEqualTo(NdjsonResultStream.Encoding.IDENTITY)
    }

    @Test
    fun `negotiateEncoding picks identity when client explicitly lists it`() {
        assertThat(stream.negotiateEncoding("identity")).isEqualTo(NdjsonResultStream.Encoding.IDENTITY)
    }

    // ----- write format -----

    @Test
    fun `writeTo emits one JSON line per row plus a trailing meta line`() {
        val out = ByteArrayOutputStream()
        val rows = listOf(sample(seq = 1L), sample(seq = 2L))
        val meta = NdjsonResultStream.MetaLine(
            hasMore = false,
            count = 2,
            lastSequence = 2L,
            partitions = PartitionAssignment(total = 64, mine = listOf(0, 5, 17)),
        )

        stream.writeTo(out, rows, meta, NdjsonResultStream.Encoding.IDENTITY)

        val text = out.toString(Charsets.UTF_8)
        val lines = text.trim('\n').split("\n")
        assertThat(lines).hasSize(3)

        val first = mapper.readTree(lines[0])
        assertThat(first["sequence"].asLong()).isEqualTo(1L)
        assertThat(first["status"].asString()).isEqualTo("COMPLETED")
        assertThat(first["routingKey"].asString()).isNotBlank

        val last = mapper.readTree(lines[2])
        assertThat(last["_meta"].asBoolean()).isTrue
        assertThat(last["hasMore"].asBoolean()).isFalse
        assertThat(last["count"].asInt()).isEqualTo(2)
        assertThat(last["lastSequence"].asLong()).isEqualTo(2L)
        assertThat(last["partitions"]["total"].asInt()).isEqualTo(64)
        val mine: List<Int> = last["partitions"]["mine"].values().map { it.asInt() }
        assertThat(mine).containsExactly(0, 5, 17)
        assertThat(last["partitions"]["hash"].asString()).isEqualTo("murmur3")
    }

    @Test
    fun `writeTo with empty rows still emits the meta line`() {
        val out = ByteArrayOutputStream()
        val meta = NdjsonResultStream.MetaLine(
            hasMore = false,
            count = 0,
            lastSequence = null,
            partitions = PartitionAssignment.empty(),
        )

        stream.writeTo(out, emptyList(), meta, NdjsonResultStream.Encoding.IDENTITY)

        val text = out.toString(Charsets.UTF_8)
        val lines = text.trim('\n').split("\n")
        assertThat(lines).hasSize(1)

        val node = mapper.readTree(lines[0])
        assertThat(node["_meta"].asBoolean()).isTrue
        assertThat(node["count"].asInt()).isZero
        assertThat(node["lastSequence"].isNull).isTrue
        assertThat(node["partitions"]["mine"].values()).isEmpty()
    }

    @Test
    fun `writeTo with gzip encoding produces a valid gzip stream`() {
        val out = ByteArrayOutputStream()
        val rows = listOf(sample(seq = 99L))
        val meta = NdjsonResultStream.MetaLine(
            hasMore = true,
            count = 1,
            lastSequence = 99L,
            partitions = PartitionAssignment(total = 64, mine = listOf(7)),
        )

        stream.writeTo(out, rows, meta, NdjsonResultStream.Encoding.GZIP)

        val decompressed = GZIPInputStream(out.toByteArray().inputStream()).bufferedReader(Charsets.UTF_8).readText()
        val lines = decompressed.trim('\n').split("\n")
        assertThat(lines).hasSize(2)
        val resultLine = mapper.readTree(lines[0])
        assertThat(resultLine["sequence"].asLong()).isEqualTo(99L)
        val metaLine = mapper.readTree(lines[1])
        assertThat(metaLine["hasMore"].asBoolean()).isTrue
    }

    @Test
    fun `writeTo serializes UUID fields as strings, not nested objects`() {
        // The contract spec lists requestId, batchId, documentId as `format: uuid`
        // strings — anything other than a flat string would break clients that
        // assert types strictly.
        val out = ByteArrayOutputStream()
        val row = sample(seq = 1L)
        stream.writeTo(
            out,
            listOf(row),
            NdjsonResultStream.MetaLine(false, 1, 1L, PartitionAssignment(64, emptyList())),
            NdjsonResultStream.Encoding.IDENTITY,
        )
        val node = mapper.readTree(out.toString(Charsets.UTF_8).split("\n").first())

        assertThat(node["requestId"].isString).isTrue
        // UUID round-trip — the string must be a parseable UUID.
        UUID.fromString(node["requestId"].asString())
    }

    @Test
    fun `writeTo serializes nullable fields as JSON null, not omitted`() {
        // The contract schema marks several fields as `[type, "null"]`. Clients
        // expect the keys to be present with null values, not absent.
        val out = ByteArrayOutputStream()
        val row = sample(seq = 1L).copy(documentId = null, error = null, batchId = null)
        stream.writeTo(
            out,
            listOf(row),
            NdjsonResultStream.MetaLine(false, 1, 1L, PartitionAssignment(64, emptyList())),
            NdjsonResultStream.Encoding.IDENTITY,
        )
        val node = mapper.readTree(out.toString(Charsets.UTF_8).split("\n").first())

        assertThat(node.has("documentId")).isTrue
        assertThat(node["documentId"].isNull).isTrue
        assertThat(node.has("error")).isTrue
        assertThat(node.has("batchId")).isTrue
    }

    private fun sample(seq: Long) = GenerationResultRow(
        sequence = seq,
        partition = 0,
        createdAt = OffsetDateTime.now(),
        requestId = GenerationRequestKey.of(UUID.randomUUID()),
        batchId = null,
        tenantKey = TenantKey.of("test-tenant"),
        routingKey = "rk-$seq",
        status = ResultStatus.COMPLETED,
        documentId = null,
        correlationId = null,
        templateId = null,
        variantId = null,
        versionId = null,
        filename = null,
        contentType = null,
        sizeBytes = null,
        error = null,
        completedAt = OffsetDateTime.now(),
    )
}
