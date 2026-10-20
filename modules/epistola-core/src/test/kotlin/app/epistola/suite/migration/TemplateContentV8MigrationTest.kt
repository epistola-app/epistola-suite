// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 2.0 stencil migrations on 1.x-shaped content (contract ADR 0006): the working copy of a stencil
 * is its draft or latest published version; a template's stencil instances become references when
 * their copy is their pinned version's content, and plain content otherwise; fills that read the
 * stencil's parameters keep doing so; and everything gets the wire-v8 names.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateContentV8MigrationTest {

    private val mapper = JsonMapper.builder().build()
    private lateinit var url: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var model: JsonNode
    private lateinit var signed: JsonNode

    @BeforeAll
    fun migrate() {
        val postgres = TestRuntimeLifecycle.postgres()
        user = postgres.username
        password = postgres.password
        val database = "template_content_v8_migration"
        DriverManager.getConnection(postgres.jdbcUrl, user, password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$database\"")
                it.execute("CREATE DATABASE \"$database\"")
            }
        }
        url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        fun flyway(target: String?) = Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(target) }
            .load()

        flyway(BEFORE).migrate()
        connect().use(::seed)
        flyway(BEFORE_VERSIONS_DROPPED).migrate()
        model = connect().use { mapper.readTree(single(it, templateModelOf("letter"))) }
        signed = connect().use { mapper.readTree(single(it, templateModelOf("signed-letter"))) }
    }

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
    fun `an instance without a stencil becomes plain content, its placeholder's default in place`() {
        assertThat(model["nodes"].has("orphan")).isFalse()
        assertThat(children(model, "root", "children")).contains("orphan-default")
    }

    @Test
    fun `a fill that reads the stencil's params makes the stencil's placeholder pass them`() {
        val content = connect().use { mapper.readTree(single(it, "SELECT content::text FROM stencils WHERE id = 'letterhead'")) }
        assertThat(content["nodes"]["lh-body"]["props"]["passParameters"].asBoolean()).isTrue()
    }

    @Test
    fun `a stencil's published versions get the same v8 content and passed parameters as its working copy`() {
        // The upgrade's first release is cut from each stencil's latest published version.
        val published = connect().use {
            mapper.readTree(single(it, "SELECT content::text FROM stencil_versions WHERE id = 2 AND stencil_resource_id = (SELECT resource_id FROM stencils WHERE id = 'letterhead')"))
        }
        assertThat(published["nodes"]["lh-body"]["props"]["passParameters"].asBoolean()).isTrue()
    }

    @Test
    fun `a stencil reads the template's data only if its own content does`() {
        connect().use {
            assertThat(single(it, "SELECT reads_template_data::text FROM stencils WHERE id = 'letterhead'")).isEqualTo("false")
            assertThat(single(it, "SELECT reads_template_data::text FROM stencils WHERE id = 'footer'")).isEqualTo("true")
        }
    }

    @Test
    fun `the working copy is the draft where there is one`() {
        val content = connect().use { mapper.readTree(single(it, "SELECT content::text FROM stencils WHERE id = 'footer'")) }
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
        connect().use {
            val workingCopy = mapper.readTree(single(it, "SELECT parameter_schema::text FROM stencils WHERE id = 'signature'"))
            assertThat(workingCopy["properties"].propertyNames().asSequence().toList()).containsExactly("signer")
            // The upgrade's first release is cut from the latest published version, so it needs it too.
            val published = mapper.readTree(
                single(it, "SELECT parameter_schema::text FROM stencil_versions WHERE id = 2 AND stencil_resource_id = (SELECT resource_id FROM stencils WHERE id = 'signature')"),
            )
            assertThat(published["properties"].propertyNames().asSequence().toList()).containsExactly("signer")
        }
        assertThat(report()).anyMatch { it.contains("stencil default/signature") && it.contains("'signer'") }
    }

    @Test
    fun `an instance whose bound parameters the stencil still declares stays a reference`() {
        assertThat(signed["nodes"]["kept"]["props"]["stencilKey"].asString()).isEqualTo("stamp")
        assertThat(signed["nodes"]["kept"]["props"]["parameterBindings"]["label"].asString()).isEqualTo("customer.name")
    }

    // ── Seed: a 1.x tenant ──────────────────────────────────────────────────

    private fun seed(connection: Connection) {
        val letterheadV1 = stencilDoc(body = "lh-body", extra = null)
        val letterheadV2 = stencilDoc(body = "lh-body", extra = "lh-logo")
        val footerText = "\"f-text\":{\"id\":\"f-text\",\"type\":\"text\",\"props\":{\"content\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"expression\",\"attrs\":{\"expression\":\"customer.name\"}}]}]}}}"
        fun footer(roots: List<String>, extraNodes: String) = "{\"modelVersion\":1,\"root\":\"root\",\"nodes\":{\"root\":{\"id\":\"root\",\"type\":\"root\",\"slots\":[\"s-root\"]},$footerText$extraNodes}," +
            "\"slots\":{\"s-root\":{\"id\":\"s-root\",\"nodeId\":\"root\",\"name\":\"children\",\"children\":[${roots.joinToString(",") { "\"$it\"" }}]}},\"themeRef\":{\"type\":\"inherit\"}}"
        val footerV1 = footer(listOf("f-text"), "")
        val footerDraft = footer(listOf("f-text", "draft-only"), ",\"draft-only\":{\"id\":\"draft-only\",\"type\":\"text\",\"props\":{}}")
        // signature: version 1 declares `signer` and `title`; version 2, the latest, declares nothing.
        val signatureDoc = expressionsDoc("sig", listOf("params.signer", "params.title"))
        val signatureSchema = SIGNATURE_SCHEMA
        // stamp: both versions declare `label`.
        val stampDoc = expressionsDoc("st", listOf("params.label"))
        val stampSchema = STAMP_SCHEMA
        connection.createStatement().use { st ->
            st.execute(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type)
                    VALUES ('default', 'acme', 'exchange.epistola.app', 'acme', 'Default', 'AUTHORED');
                INSERT INTO stencils (id, tenant_key, catalog_id, name) VALUES
                    ('letterhead', 'acme', (SELECT catalog_id FROM catalogs), 'Letterhead'),
                    ('footer', 'acme', (SELECT catalog_id FROM catalogs), 'Footer');
                INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, status, published_at) VALUES
                    (1, 'acme', (SELECT resource_id FROM stencils WHERE id = 'letterhead'), '$letterheadV1', 'published', NOW()),
                    (2, 'acme', (SELECT resource_id FROM stencils WHERE id = 'letterhead'), '$letterheadV2', 'published', NOW()),
                    (1, 'acme', (SELECT resource_id FROM stencils WHERE id = 'footer'), '$footerV1', 'published', NOW()),
                    (2, 'acme', (SELECT resource_id FROM stencils WHERE id = 'footer'), '$footerDraft', 'draft', NULL);
                INSERT INTO stencils (id, tenant_key, catalog_id, name) VALUES
                    ('signature', 'acme', (SELECT catalog_id FROM catalogs), 'Signature'),
                    ('stamp', 'acme', (SELECT catalog_id FROM catalogs), 'Stamp');
                INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, parameter_schema, status, published_at) VALUES
                    (1, 'acme', (SELECT resource_id FROM stencils WHERE id = 'signature'), '$signatureDoc', '$signatureSchema', 'published', NOW()),
                    (2, 'acme', (SELECT resource_id FROM stencils WHERE id = 'signature'), '$signatureDoc', NULL, 'published', NOW()),
                    (1, 'acme', (SELECT resource_id FROM stencils WHERE id = 'stamp'), '$stampDoc', '$stampSchema', 'published', NOW()),
                    (2, 'acme', (SELECT resource_id FROM stencils WHERE id = 'stamp'), '$stampDoc', '$stampSchema', 'published', NOW());
                INSERT INTO document_templates (id, tenant_key, catalog_id, name) VALUES
                    ('letter', 'acme', (SELECT catalog_id FROM catalogs), 'Letter'),
                    ('signed-letter', 'acme', (SELECT catalog_id FROM catalogs), 'Signed letter');
                INSERT INTO template_variants (id, tenant_key, template_resource_id, title)
                    SELECT 'default', 'acme', resource_id, 'Default' FROM document_templates;
                INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status) VALUES
                    (1, 'acme', (SELECT resource_id FROM document_templates WHERE id = 'letter'), 'default', '${templateDoc()}', 'draft'),
                    (1, 'acme', (SELECT resource_id FROM document_templates WHERE id = 'signed-letter'), 'default', '${signedDoc()}', 'draft');
                """.trimIndent(),
            )
        }
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
        return """{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":["s-root"]},${dropped.first},${kept.first}},""" +
            """"slots":{"s-root":{"id":"s-root","nodeId":"root","name":"children","children":["dropped","kept"]},${dropped.second},${kept.second}},"themeRef":{"type":"inherit"}}"""
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun connect(): Connection = DriverManager.getConnection(url, user, password)

    private fun single(connection: Connection, sql: String): String = connection.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    private fun report(): List<String> = connect().use { connection ->
        mapper.readTree(single(connection, "SELECT value::text FROM app_metadata WHERE key = 'upgrade-2.0-template-content'"))
            .iterator().asSequence().map { it["subject"].asString() + " " + it["message"].asString() }.toList()
    }

    private fun templateModelOf(templateKey: String) = "SELECT v.template_model::text FROM template_versions v JOIN document_templates t ON t.resource_id = v.template_resource_id WHERE t.id = '$templateKey'"

    private fun children(document: JsonNode, nodeId: String, slotName: String): List<String> {
        val slotIds = document["nodes"][nodeId]["slots"].iterator().asSequence().map { it.asString() }
        val slot = slotIds.map { document["slots"][it] }.first { it["name"].asString() == slotName }
        return slot["children"].iterator().asSequence().map { it.asString() }.toList()
    }

    private companion object {
        /** The last migration before the stencil working copy. */
        const val BEFORE = "20261018200300"

        /** The last migration that still has the 1.x version tables this migration rewrote; the upgrade drops them. */
        const val BEFORE_VERSIONS_DROPPED = "20261018201300"

        const val SIGNATURE_SCHEMA = """{"type":"object","properties":{"signer":{"type":"string"},"title":{"type":"string","default":"Director"}}}"""
        const val STAMP_SCHEMA = """{"type":"object","properties":{"label":{"type":"string"}}}"""
    }
}
