// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.frozen

import app.epistola.catalog.protocol.CatalogKeys
import db.migration.core.frozen.CanonicalJson
import db.migration.core.frozen.DataExampleKeys
import db.migration.core.frozen.Envelopes
import db.migration.core.frozen.ReferenceWalker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.Base64

/**
 * The frozen toolkit of the 2.0 upgrade (`db.migration.core.frozen`, ADR 0035) gives exactly what the
 * conversions it was taken from gave, recorded in `fixtures/storage/v1.json`: every envelope by its
 * canonical JSON and content hash, a file's hash, the released forms of a document, and data example
 * keys, which the contract's [CatalogKeys] derives alike. An installation that upgraded with the toolkit
 * holds content under these hashes, so a changed output for an existing input fails here.
 */
class FrozenToolkitGoldenVectorsTest {

    private val mapper = JsonMapper.builder().build()
    private val vectors: JsonNode = mapper.readTree(javaClass.getResource("/fixtures/storage/v1.json")!!.readText())

    @Test
    fun `a file is stored under the SHA-256 of its bytes`() {
        assertThat(vectors.path("bytes").size()).isGreaterThan(1)
        for (case in vectors.path("bytes")) {
            assertThat(CanonicalJson.sha256Hex(Base64.getDecoder().decode(case.path("base64").asString()))).isEqualTo(case.path("sha256").asString())
        }
    }

    @Test
    fun `every envelope is the content its 1_x columns were stored as, under the same hash`() {
        assertThat(vectors.path("envelopes").size()).isGreaterThan(10)
        for (case in vectors.path("envelopes")) {
            val payload = envelope(case.path("type").asString(), case.path("input"))
            val canonical = CanonicalJson.canonical(payload)
            assertThat(canonical).`as`(case.path("name").asString()).isEqualTo(case.path("canonical").asString())
            assertThat(CanonicalJson.contentHash(canonical)).`as`(case.path("name").asString()).isEqualTo(case.path("contentHash").asString())
            assertThat(CanonicalJson.canonical(case.path("payload"))).isEqualTo(canonical)
        }
    }

    @Test
    fun `a document's released forms are what a release of a variant and of a stencil keep`() {
        for (case in vectors.path("released")) {
            val catalogKey = case.path("catalogKey").asString()
            assertThat(ReferenceWalker.releasedModel(case.path("document"), catalogKey)).`as`(case.path("name").asString()).isEqualTo(case.path("releasedModel"))
            assertThat(ReferenceWalker.releasedForm(case.path("document"), catalogKey)).`as`(case.path("name").asString()).isEqualTo(case.path("releasedForm"))
        }
    }

    @Test
    fun `a data example is keyed as the contract keys it`() {
        for (case in vectors.path("dataExampleKeys")) {
            val name = case.path("name").asString()
            val taken = case.path("taken").toList().map { it.asString() }.toSet()
            assertThat(DataExampleKeys.dataExampleKey(name, taken)).`as`(name).isEqualTo(case.path("key").asString())
            assertThat(CatalogKeys.deriveDataExampleKey(name, taken)).`as`("the contract, for $name").isEqualTo(case.path("key").asString())
        }
        for (case in vectors.path("examples")) {
            assertThat(DataExampleKeys.examples(case.path("input"))).isEqualTo(case.path("output"))
        }
    }

    private fun envelope(type: String, input: JsonNode): JsonNode {
        fun text(field: String): String? = input.path(field).takeUnless { it.isNull || it.isMissingNode }?.asString()
        fun json(field: String): JsonNode? = text(field)?.let(mapper::readTree)
        return when (type) {
            "theme" -> Envelopes.theme(
                text("key")!!,
                text("name")!!,
                text("description"),
                json("documentStyles"),
                json("pageSettings"),
                json("blockStylePresets"),
                text("spacingUnit")?.let(::BigDecimal),
            )

            "codeList" -> Envelopes.codeList(
                text("key")!!,
                text("name")!!,
                text("description"),
                input.path("entries").toList().map { Envelopes.CodeListEntry(it.path("code").asString(), it.path("label").asString(), it.path("sortOrder").asInt(), it.path("hidden").asBoolean()) },
                text("sourceType")!!,
                text("sourceUrl"),
                text("authType")!!,
                text("credentialId"),
            )

            "attribute" -> Envelopes.attribute(text("key")!!, text("name")!!, json("allowedValues"), text("bindingAlias"), text("bindingKey"))

            "font" -> Envelopes.font(
                text("key")!!,
                text("name")!!,
                text("kind")!!,
                input.path("faces").toList().map { Envelopes.Face(it.path("weight").asInt(), it.path("italic").asBoolean(), it.path("mediaType").asString(), it.path("contentHash").asString()) },
            )

            "image" -> Envelopes.image(
                text("key")!!,
                text("name")!!,
                text("mediaType")!!,
                input.path("width").takeIf { it.isNumber }?.asInt(),
                input.path("height").takeIf { it.isNumber }?.asInt(),
                text("contentHash"),
            )

            "stencil" -> Envelopes.stencil(
                text("key")!!,
                text("catalogKey")!!,
                text("name")!!,
                text("description"),
                json("tags"),
                json("content")!!,
                json("parameterSchema"),
                input.path("readsTemplateData").asBoolean(),
            )

            "template" -> Envelopes.template(
                text("key")!!, text("name")!!, text("description"), text("themeKey"), text("themeCatalogKey"), input.path("pdfaEnabled").asBoolean(),
                json("dataModel"), DataExampleKeys.wireForm(json("dataExamples")),
                input.path("variants").toList().map { Envelopes.VariantEntry(it.path("key").asString(), it.path("attributes").takeUnless { a -> a.isNull }?.asString()?.let(mapper::readTree), it.path("isDefault").asBoolean()) },
            )

            "variant" -> Envelopes.variant(text("title")!!, input.path("model"), json("metadata"), text("description"))

            else -> error("no envelope of type $type")
        }
    }
}
