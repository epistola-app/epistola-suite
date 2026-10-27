// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import db.migration.core.frozen.TemplateContentV8
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The 2.0 upgrade's conversion of 1.x template content ([TemplateContentV8], contract ADR 0006), on
 * 1.x documents: the working copy of a stencil is its draft or latest published version; a template's
 * stencil instances become references when their copy is their pinned version's content, and plain
 * content otherwise; fills that read the stencil's parameters keep doing so; and everything gets the
 * wire-v8 names. Pure: no database (the steps that store the result are held to the chain it
 * replaced by `TwoZeroChainEquivalenceIT`).
 */
class TemplateContentV8Test {

    private val mapper = JsonMapper.builder().build()
    private val result = convert()
    private val model: JsonNode = result.models.getValue(TemplateContentV8.key(TENANT, LETTER, "default", 1))
    private val signed: JsonNode = result.models.getValue(TemplateContentV8.key(TENANT, SIGNED_LETTER, "default", 1))

    @Test
    fun `an instance whose copy is its current version becomes a reference plus its fill`() {
        val instance = model["nodes"]["current"]
        assertThat(instance["props"].propertyNames().asSequence().toSet()).containsExactlyInAnyOrder("stencilKey")
        assertThat(children(model, "current", "children")).containsExactly("current:fill:body")
        assertThat(children(model, "current:fill:body", "fill")).containsExactly("current-fill")
        assertThat(model["nodes"].has("current-logo")).isFalse()
    }

    @Test
    fun `an instance of an older version becomes a reference too, and is reported`() {
        assertThat(model["nodes"]["older"]["props"]["stencilKey"].asString()).isEqualTo("letterhead")
        assertThat(report()).anyMatch { it.contains("instance older") && it.contains("now renders the stencil as it is") }
    }

    @Test
    fun `an instance whose copy was changed becomes plain content with its fill in place`() {
        assertThat(model["nodes"].has("changed")).isFalse()
        assertThat(children(model, "root", "children")).containsSubsequence("changed-logo", "changed-extra", "changed-fill")
        assertThat(report()).anyMatch { it.contains("instance changed") && it.contains("became plain content") }
    }

    @Test
    fun `an instance kept without a copy stays a reference to its stencil`() {
        assertThat(model["nodes"]["bare"]["props"]["stencilKey"].asString()).isEqualTo("letterhead")
        assertThat(children(model, "root", "children")).contains("bare")
        assertThat(report()).noneMatch { it.contains("instance bare") && it.contains("became plain content") }
    }

    @Test
    fun `an instance without a stencil becomes plain content, its placeholder's default in place`() {
        assertThat(model["nodes"].has("orphan")).isFalse()
        assertThat(children(model, "root", "children")).contains("orphan-default")
    }

    @Test
    fun `a fill that reads the stencil's params makes the stencil's placeholder pass them`() {
        val content = stencil("letterhead").content
        assertThat(content["nodes"]["lh-body"]["props"]["passParameters"].asBoolean()).isTrue()
    }

    @Test
    fun `a stencil's published versions get the same v8 content and passed parameters as its working copy`() {
        // The upgrade's first release is cut from each stencil's latest published version.
        val published = stencil("letterhead").published!!.content
        assertThat(published["nodes"]["lh-body"]["props"]["passParameters"].asBoolean()).isTrue()
    }

    @Test
    fun `a stencil reads the template's data only if its own content does`() {
        assertThat(stencil("letterhead").readsTemplateData).isFalse()
        assertThat(stencil("footer").readsTemplateData).isTrue()
    }

    @Test
    fun `the working copy is the draft where there is one`() {
        val content = stencil("footer").content
        assertThat(content["nodes"].has("draft-only")).isTrue()
    }

    @Test
    fun `content gets the wire-v8 names`() {
        assertThat(model["nodes"]["image"]["props"]["imageKey"].asString()).isEqualTo("logo")
        assertThat(model["nodes"]["image"]["props"].has("assetId")).isFalse()
        assertThat(model["themeRef"]["themeKey"].asString()).isEqualTo("corporate")
    }

    @Test
    fun `a parameter a template binds that the stencil no longer declares is declared again, and the instance stays a reference`() {
        // 1.x rendered the pinned copy with version 1's parameters; the latest version declares none,
        // so the reference would render `params.signer` empty. The stencil gets the declaration back.
        assertThat(signed["nodes"]["dropped"]["props"]["stencilKey"].asString()).isEqualTo("signature")
        val workingCopy = stencil("signature").parameterSchema!!
        assertThat(workingCopy["properties"].propertyNames().asSequence().toList()).containsExactly("signer")
        // The "Published in 1.x" draft holds the latest published version, so it needs it too.
        val published = stencil("signature").published!!.parameterSchema!!
        assertThat(published["properties"].propertyNames().asSequence().toList()).containsExactly("signer")
        assertThat(report()).anyMatch { it.contains("stencil default/signature") && it.contains("'signer'") }
    }

    @Test
    fun `an instance that becomes plain content while reading its parameters is reported with them`() {
        // Plain content gets no parameters, so its params.label renders empty; the report says which.
        assertThat(signed["nodes"].has("altered")).isFalse()
        assertThat(report()).anyMatch { it.contains("instance altered") && it.contains("params.label") }
    }

    @Test
    fun `an instance whose bound parameters the stencil still declares stays a reference`() {
        assertThat(signed["nodes"]["kept"]["props"]["stencilKey"].asString()).isEqualTo("stamp")
        assertThat(signed["nodes"]["kept"]["props"]["parameterBindings"]["label"].asString()).isEqualTo("customer.name")
    }

    // ── 1.x content ─────────────────────────────────────────────────────────

    private fun convert(): TemplateContentV8.Result {
        val letterheadV1 = stencilDoc(body = "lh-body", extra = null)
        val letterheadV2 = stencilDoc(body = "lh-body", extra = "lh-logo")
        val footerText = "\"f-text\":{\"id\":\"f-text\",\"type\":\"text\",\"props\":{\"content\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"expression\",\"attrs\":{\"expression\":\"customer.name\"}}]}]}}}"
        fun footer(roots: List<String>, extraNodes: String) = "{\"modelVersion\":1,\"root\":\"root\",\"nodes\":{\"root\":{\"id\":\"root\",\"type\":\"root\",\"slots\":[\"s-root\"]},$footerText$extraNodes}," +
            "\"slots\":{\"s-root\":{\"id\":\"s-root\",\"nodeId\":\"root\",\"name\":\"children\",\"children\":[${roots.joinToString(",") { "\"$it\"" }}]}},\"themeRef\":{\"type\":\"inherit\"}}"
        val footerV1 = footer(listOf("f-text"), "")
        val footerDraft = footer(listOf("f-text", "draft-only"), ",\"draft-only\":{\"id\":\"draft-only\",\"type\":\"text\",\"props\":{}}")
        // signature: version 1 declares `signer` and `title`; version 2, the latest, declares nothing.
        val signatureDoc = expressionsDoc("sig", listOf("params.signer", "params.title"))
        // stamp: both versions declare `label`.
        val stampDoc = expressionsDoc("st", listOf("params.label"))
        fun version(stencil: String, id: Int, doc: String, status: String, schema: String? = null) = TemplateContentV8.StencilVersion(TENANT, stencil, id, status, mapper.readTree(doc), schema?.let(mapper::readTree))
        val versions = listOf(
            version("letterhead", 1, letterheadV1, "published"),
            version("letterhead", 2, letterheadV2, "published"),
            version("footer", 1, footerV1, "published"),
            version("footer", 2, footerDraft, "draft"),
            version("signature", 1, signatureDoc, "published", SIGNATURE_SCHEMA),
            version("signature", 2, signatureDoc, "published"),
            version("stamp", 1, stampDoc, "published", STAMP_SCHEMA),
            version("stamp", 2, stampDoc, "published", STAMP_SCHEMA),
        )
        // Each stencil's working copy, as OneXContent picks it: its draft, else its latest published version.
        val stencils = listOf("letterhead", "footer", "signature", "stamp").map { key ->
            val working = versions.filter { it.resourceId == key }.minWith(compareBy<TemplateContentV8.StencilVersion> { if (it.status == "draft") 0 else 1 }.thenByDescending { it.id })
            TemplateContentV8.Stencil(TENANT, key, CATALOG, key, working.content as ObjectNode, working.parameterSchema)
        }
        val templates = listOf(
            TemplateContentV8.TemplateVersion(TENANT, LETTER, "default", 1, mapper.readTree(templateDoc()), CATALOG, "letter"),
            TemplateContentV8.TemplateVersion(TENANT, SIGNED_LETTER, "default", 1, mapper.readTree(signedDoc()), CATALOG, "signed-letter"),
        )
        return TemplateContentV8.convert(stencils, versions, templates)
    }

    /** A letterhead stencil document: optionally a logo, then a `body` placeholder with default text. */
    private fun stencilDoc(body: String, extra: String?): String {
        val roots = listOfNotNull(extra, body).joinToString(",") { "\"$it\"" }
        val extraNode = extra?.let { ",\"$it\":{\"id\":\"$it\",\"type\":\"text\",\"props\":{\"content\":null}}" }.orEmpty()
        return """{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":["s-root"]},"$body":{"id":"$body","type":"placeholder","slots":["$body-d","$body-f"],"props":{"name":"body"}},"$body-default":{"id":"$body-default","type":"text","props":{"content":null}}$extraNode},"slots":{"s-root":{"id":"s-root","nodeId":"root","name":"children","children":[$roots]},"$body-d":{"id":"$body-d","nodeId":"$body","name":"default","children":["$body-default"]},"$body-f":{"id":"$body-f","nodeId":"$body","name":"fill","children":[]}},"themeRef":{"type":"inherit"}}"""
    }

    /**
     * Five instances in one template: `current` (copy of v2, filled, fill reads params), `older` (copy
     * of v1), `changed` (copy of v2 plus a node), `orphan` (names no stencil), and an image.
     */
    private fun templateDoc(): String {
        val nodes = mutableListOf("\"root\":{\"id\":\"root\",\"type\":\"root\",\"slots\":[\"s-root\"]}")
        val slots = mutableListOf<String>()
        val rootChildren = mutableListOf<String>()
        fun instance(id: String, stencil: String?, version: Int, logo: Boolean, extra: Boolean, fill: String?) {
            rootChildren += id
            val props = listOfNotNull(stencil?.let { "\"stencilId\":\"$it\"" }, "\"version\":$version").joinToString(",")
            nodes += "\"$id\":{\"id\":\"$id\",\"type\":\"stencil\",\"slots\":[\"$id-c\"],\"props\":{$props}}"
            val copy = listOfNotNull(if (logo) "$id-logo" else null, if (extra) "$id-extra" else null, "$id-body")
            slots += "\"$id-c\":{\"id\":\"$id-c\",\"nodeId\":\"$id\",\"name\":\"children\",\"children\":[${copy.joinToString(",") { "\"$it\"" }}]}"
            if (logo) nodes += "\"$id-logo\":{\"id\":\"$id-logo\",\"type\":\"text\",\"props\":{\"content\":null}}"
            if (extra) nodes += "\"$id-extra\":{\"id\":\"$id-extra\",\"type\":\"text\",\"props\":{\"content\":null}}"
            nodes += "\"$id-body\":{\"id\":\"$id-body\",\"type\":\"placeholder\",\"slots\":[\"$id-bd\",\"$id-bf\"],\"props\":{\"name\":\"body\"}}"
            nodes += "\"$id-default\":{\"id\":\"$id-default\",\"type\":\"text\",\"props\":{\"content\":null}}"
            slots += "\"$id-bd\":{\"id\":\"$id-bd\",\"nodeId\":\"$id-body\",\"name\":\"default\",\"children\":[\"$id-default\"]}"
            slots += "\"$id-bf\":{\"id\":\"$id-bf\",\"nodeId\":\"$id-body\",\"name\":\"fill\",\"children\":[${fill?.let { "\"$id-fill\"" }.orEmpty()}]}"
            if (fill != null) {
                nodes += "\"$id-fill\":{\"id\":\"$id-fill\",\"type\":\"text\",\"props\":{\"content\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"expression\",\"attrs\":{\"expression\":\"$fill\"}}]}]}}}"
            }
        }
        instance("current", "letterhead", 2, logo = true, extra = false, fill = "params.name")
        instance("older", "letterhead", 1, logo = false, extra = false, fill = null)
        instance("changed", "letterhead", 2, logo = true, extra = true, fill = "customer.name")
        instance("orphan", null, 1, logo = false, extra = false, fill = null)
        // 1.x also kept an instance as a bare reference, without a copy of the stencil, as the demo
        // catalog's Hello World does: it rendered the stencil itself.
        rootChildren += "bare"
        nodes += "\"bare\":{\"id\":\"bare\",\"type\":\"stencil\",\"slots\":[],\"props\":{\"stencilId\":\"letterhead\",\"version\":2}}"
        rootChildren += "image"
        nodes += "\"image\":{\"id\":\"image\",\"type\":\"image\",\"props\":{\"assetId\":\"logo\"}}"
        slots += "\"s-root\":{\"id\":\"s-root\",\"nodeId\":\"root\",\"name\":\"children\",\"children\":[${rootChildren.joinToString(",") { "\"$it\"" }}]}"
        return """{"modelVersion":1,"root":"root","nodes":{${nodes.joinToString(",")}},"slots":{${slots.joinToString(",")}},"themeRef":{"type":"override","themeId":"corporate"}}"""
    }

    /** A stencil document of one text node per expression, ids `<prefix>-0`, `<prefix>-1`, …. */
    private fun expressionsDoc(prefix: String, expressions: List<String>): String {
        val ids = expressions.indices.map { "$prefix-$it" }
        val texts = ids.zip(expressions).joinToString(",") { (id, expression) -> "\"$id\":${expressionText(id, expression)}" }
        return """{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":["s-root"]},$texts},"slots":{"s-root":{"id":"s-root","nodeId":"root","name":"children","children":[${ids.joinToString(",") { "\"$it\"" }}]}},"themeRef":{"type":"inherit"}}"""
    }

    private fun expressionText(id: String, expression: String) = """{"id":"$id","type":"text","props":{"content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"expression","attrs":{"expression":"$expression"}}]}]}}}"""

    /**
     * Two instances: `dropped` (signature version 1, binding `signer`, which the latest version no
     * longer declares) and `kept` (stamp version 1, binding `label`, still declared).
     */
    private fun signedDoc(): String {
        fun instance(id: String, stencil: String, schema: String, bindings: String, copy: List<Pair<String, String>>): Pair<String, String> {
            val nodes = listOf(
                "\"$id\":{\"id\":\"$id\",\"type\":\"stencil\",\"slots\":[\"$id-c\"],\"props\":{\"stencilId\":\"$stencil\",\"version\":1,\"parameterBindings\":$bindings,\"parameterSchemaSnapshot\":$schema}}",
            ) + copy.map { (nodeId, expression) -> "\"$nodeId\":${expressionText(nodeId, expression)}" }
            val slots = "\"$id-c\":{\"id\":\"$id-c\",\"nodeId\":\"$id\",\"name\":\"children\",\"children\":[${copy.joinToString(",") { "\"${it.first}\"" }}]}"
            return nodes.joinToString(",") to slots
        }
        val dropped = instance("dropped", "signature", SIGNATURE_SCHEMA, """{"signer":"customer.name"}""", listOf("dropped-name" to "params.signer", "dropped-title" to "params.title"))
        val kept = instance("kept", "stamp", STAMP_SCHEMA, """{"label":"customer.name"}""", listOf("kept-label" to "params.label"))
        // A copy that differs from stamp version 1 (an extra node) and reads its parameter.
        val altered = instance(
            "altered",
            "stamp",
            STAMP_SCHEMA,
            """{"label":"customer.name"}""",
            listOf("altered-label" to "params.label", "altered-extra" to "customer.name"),
        )
        return """{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":["s-root"]},${dropped.first},${kept.first},${altered.first}},""" +
            """"slots":{"s-root":{"id":"s-root","nodeId":"root","name":"children","children":["dropped","kept","altered"]},${dropped.second},${kept.second},${altered.second}},"themeRef":{"type":"inherit"}}"""
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun stencil(key: String): TemplateContentV8.ConvertedStencil = result.stencils.getValue(TemplateContentV8.key(TENANT, key))

    private fun report(): List<String> = result.report.map { it["subject"].asString() + " " + it["message"].asString() }

    private fun children(document: JsonNode, nodeId: String, slotName: String): List<String> {
        val slotIds = document["nodes"][nodeId]["slots"].iterator().asSequence().map { it.asString() }
        val slot = slotIds.map { document["slots"][it] }.first { it["name"].asString() == slotName }
        return slot["children"].iterator().asSequence().map { it.asString() }.toList()
    }

    private companion object {
        const val TENANT = "acme"
        const val CATALOG = "default"
        const val LETTER = "letter"
        const val SIGNED_LETTER = "signed-letter"

        const val SIGNATURE_SCHEMA = """{"type":"object","properties":{"signer":{"type":"string"},"title":{"type":"string","default":"Director"}}}"""
        const val STAMP_SCHEMA = """{"type":"object","properties":{"label":{"type":"string"}}}"""
    }
}
