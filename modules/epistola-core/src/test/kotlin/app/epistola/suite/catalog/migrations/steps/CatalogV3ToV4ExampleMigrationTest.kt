package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.migrations.MigrationContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/** Unit tests for the example `v3 → v4` catalog wire-format migration. */
class CatalogV3ToV4ExampleMigrationTest {

    private val mapper = jsonMapper()
    private val migration = CatalogV3ToV4ExampleMigration()
    private val ctx = MigrationContext(sourceVersion = 3, targetVersion = 4)

    private fun templateDetail(): ObjectNode = mapper.readTree(
        """
        {
          "schemaVersion": 3,
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

    private fun noticeText(detail: ObjectNode): String = detail.get("resource").get("templateModel").get("nodes").get("n-migration-notice-v4")
        .get("props").get("content").get("content").get(0).get("content").get(0).get("text").asString()

    @Test
    fun `declares the v3 to v4 step`() {
        assertThat(migration.from).isEqualTo(3)
        assertThat(migration.to).isEqualTo(4)
    }

    @Test
    fun `appends an upgrade-naar-versie-4 text block to a template`() {
        val result = migration.migrateResourceDetail("template", templateDetail(), ctx)
        val model = result.get("resource").get("templateModel")
        val block = model.get("nodes").get("n-migration-notice-v4")
        assertThat(block.get("type").asString()).isEqualTo("text")
        assertThat(noticeText(result)).isEqualTo("upgrade naar versie 4")
        // wired into the root's children slot (appended last)
        val children = model.get("slots").get("s-root").get("children")
        assertThat(children.last().asString()).isEqualTo("n-migration-notice-v4")
    }

    @Test
    fun `is idempotent - re-running adds no second block`() {
        val detail = templateDetail()
        migration.migrateResourceDetail("template", detail, ctx)
        migration.migrateResourceDetail("template", detail, ctx)
        val children = detail.get("resource").get("templateModel").get("slots").get("s-root").get("children")
        assertThat(children.size()).isEqualTo(1)
    }

    @Test
    fun `is a no-op for a non-template resource`() {
        val json = """{ "schemaVersion": 3, "resource": { "type": "attribute", "slug": "x", "name": "X" } }"""
        val result = migration.migrateResourceDetail("attribute", mapper.readTree(json) as ObjectNode, ctx)
        assertThat(result).isEqualTo(mapper.readTree(json))
    }
}
