package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.migrations.MigrationContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/** Unit tests for the no-op `v4 → v5` version-bump migration. */
class CatalogV4ToV5MigrationTest {

    private val mapper = jsonMapper()
    private val migration = CatalogV4ToV5Migration()
    private val ctx = MigrationContext(sourceVersion = 4, targetVersion = 5)

    private fun obj(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

    @Test
    fun `declares the v4 to v5 step`() {
        assertThat(migration.from).isEqualTo(4)
        assertThat(migration.to).isEqualTo(5)
    }

    @Test
    fun `leaves the manifest content unchanged (pure version bump)`() {
        val tree = obj("""{ "schemaVersion": 4, "catalog": { "slug": "x", "name": "X" } }""")
        val result = migration.migrateManifest(tree, ctx)
        assertThat(result).isEqualTo(obj("""{ "schemaVersion": 4, "catalog": { "slug": "x", "name": "X" } }"""))
    }

    @Test
    fun `leaves a resource detail unchanged (pure version bump)`() {
        val tree = obj("""{ "schemaVersion": 4, "resource": { "type": "theme", "slug": "corp", "name": "Corporate" } }""")
        val result = migration.migrateResourceDetail("theme", tree, ctx)
        assertThat(result).isEqualTo(obj("""{ "schemaVersion": 4, "resource": { "type": "theme", "slug": "corp", "name": "Corporate" } }"""))
    }
}
