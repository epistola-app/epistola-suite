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

    private fun obj(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

    @Test
    fun `declares the v3 to v4 step`() {
        assertThat(migration.from).isEqualTo(3)
        assertThat(migration.to).isEqualTo(4)
    }

    @Test
    fun `manifest renames legacy catalog title to name`() {
        val tree = obj("""{ "schemaVersion": 3, "catalog": { "slug": "x", "title": "Acme" } }""")
        val result = migration.migrateManifest(tree, ctx)
        val catalog = result.get("catalog") as ObjectNode
        assertThat(catalog.get("name").asString()).isEqualTo("Acme")
        assertThat(catalog.has("title")).isFalse()
    }

    @Test
    fun `resource detail renames legacy displayName to name`() {
        val tree = obj("""{ "schemaVersion": 3, "resource": { "type": "attribute", "slug": "country", "displayName": "Country" } }""")
        val result = migration.migrateResourceDetail("attribute", tree, ctx)
        val resource = result.get("resource") as ObjectNode
        assertThat(resource.get("name").asString()).isEqualTo("Country")
        assertThat(resource.has("displayName")).isFalse()
    }

    @Test
    fun `is a no-op when the current field already exists (idempotent)`() {
        val manifest = obj("""{ "catalog": { "slug": "x", "name": "Acme", "title": "stale" } }""")
        val catalog = migration.migrateManifest(manifest, ctx).get("catalog") as ObjectNode
        // name is kept; the legacy field is left untouched because name was present
        assertThat(catalog.get("name").asString()).isEqualTo("Acme")
        assertThat(catalog.get("title").asString()).isEqualTo("stale")
    }

    @Test
    fun `leaves a current-shape tree unchanged`() {
        val tree = obj("""{ "resource": { "type": "theme", "slug": "corp", "name": "Corporate" } }""")
        val result = migration.migrateResourceDetail("theme", tree, ctx)
        assertThat((result.get("resource") as ObjectNode).get("name").asString()).isEqualTo("Corporate")
        assertThat((result.get("resource") as ObjectNode).has("displayName")).isFalse()
    }
}
