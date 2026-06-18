package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.migrations.MigrationContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/** Unit tests for the example `v4 → v5` catalog wire-format migration. */
class CatalogV4ToV5MigrationTest {

    private val mapper = jsonMapper()
    private val migration = CatalogV4ToV5Migration()
    private val ctx = MigrationContext(sourceVersion = 4, targetVersion = 5)

    private fun templateDetail(): ObjectNode = mapper.readTree(
        """
        {
          "schemaVersion": 4,
          "resource": {
            "type": "template",
            "slug": "t",
            "name": "T",
            "templateModel": {
              "root": "n-root",
              "nodes": { "n-root": { "id": "n-root", "type": "root", "slots": ["s-root"] } },
              "slots": { "s-root": { "id": "s-root", "nodeId": "n-root", "name": "children", "children": [] } }
            }
          }
        }
        """.trimIndent(),
    ) as ObjectNode

    private fun noticeText(detail: ObjectNode): String = detail.get("resource").get("templateModel").get("nodes").get("n-migration-notice-v5")
        .get("props").get("content").get("content").get(0).get("content").get(0).get("text").asString()

    @Test
    fun `declares the v4 to v5 step`() {
        assertThat(migration.from).isEqualTo(4)
        assertThat(migration.to).isEqualTo(5)
    }

    @Test
    fun `appends an update-naar-versie-5 text block to a template`() {
        val result = migration.migrateResourceDetail("template", templateDetail(), ctx)
        val model = result.get("resource").get("templateModel")
        assertThat(model.get("nodes").has("n-migration-notice-v5")).isTrue()
        assertThat(noticeText(result)).isEqualTo("update naar versie 5")
        assertThat(model.get("slots").get("s-root").get("children").last().asString())
            .isEqualTo("n-migration-notice-v5")
    }

    @Test
    fun `is a no-op for a non-template resource`() {
        val json = """{ "schemaVersion": 4, "resource": { "type": "theme", "slug": "corp", "name": "Corporate" } }"""
        val result = migration.migrateResourceDetail("theme", mapper.readTree(json) as ObjectNode, ctx)
        assertThat(result).isEqualTo(mapper.readTree(json))
    }
}
