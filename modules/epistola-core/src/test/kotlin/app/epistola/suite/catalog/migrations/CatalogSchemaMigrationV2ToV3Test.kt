package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.migrateResourceDetailTree
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/**
 * Unit checks for the v2 → v3 step. The boundary was purely additive
 * (`codeListBinding` arrived as optional; inline `allowedValues` stays valid), so
 * the step is identity — it only widens the supported baseline to 2. These tests
 * pin that it does **not** drop or rewrite anything.
 */
class CatalogSchemaMigrationV2ToV3Test {

    private val mapper = jsonMapper()
    private val step = CatalogSchemaMigrationV2ToV3()
    private val ctx = MigrationContext(sourceVersion = 2, targetVersion = 3, manifest = null)

    private fun detail(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

    @Test
    fun `advances v2 to v3`() {
        assertThat(step.from).isEqualTo(2)
        assertThat(step.to).isEqualTo(3)
    }

    @Test
    fun `leaves an inline-allowedValues attribute untouched`() {
        val input = detail("""{"schemaVersion":2,"resource":{"type":"attribute","slug":"language","name":"Language","allowedValues":["nl","en"]}}""")
        val before = input.deepCopy()
        val out = step.migrateResourceDetail("attribute", input, ctx)
        assertThat(out).isEqualTo(before) // nothing dropped, nothing added
    }

    @Test
    fun `chain passes a v2 attribute through and re-stamps schemaVersion to 3`() {
        val byFrom = mapOf<Int, CatalogSchemaMigration>(2 to CatalogSchemaMigrationV2ToV3())
        val out = migrateResourceDetailTree(
            detail("""{"schemaVersion":2,"resource":{"type":"attribute","slug":"language","name":"Language","allowedValues":["nl"]}}"""),
            sourceVersion = 2,
            byFrom = byFrom,
            baseline = 2,
            current = 3,
        )
        assertThat(out.path("resource").path("allowedValues").path(0).asString()).isEqualTo("nl")
        assertThat(out.path("resource").has("codeListBinding")).isFalse()
        assertThat(out.path("schemaVersion").asInt()).isEqualTo(3)
    }

    @Test
    fun `appends a version-3 notice text block to templates`() {
        val out = step.migrateResourceDetail(
            "template",
            detail(
                """{"schemaVersion":2,"resource":{"type":"template","slug":"t","name":"T","templateModel":{"root":"r","nodes":{"r":{"id":"r","type":"root","slots":["s"]}},"slots":{"s":{"id":"s","nodeId":"r","name":"children","children":[]}}},"variants":[]}}""",
            ),
            ctx,
        )
        val model = out.path("resource").path("templateModel")
        assertThat(model.path("slots").path("s").path("children").path(0).asString()).isEqualTo("n-migration-notice-v3")
        val noticeText = model.path("nodes").path("n-migration-notice-v3")
            .path("props").path("content").path("content").path(0).path("content").path(0).path("text").asString()
        assertThat(noticeText).isEqualTo("migratie naar versie 3")
    }
}
