package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.CatalogPart
import app.epistola.suite.catalog.migrations.MigrationContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Unit coverage for the first real wire migration: the stencil v1 → v2 step that
 * makes a stencil's `version` explicit. Pure `JsonNode` in → out, the contract
 * every [app.epistola.suite.catalog.migrations.CatalogSchemaMigration] honours.
 */
class StencilV1ToV2RequireVersionMigrationTest {

    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val migration = StencilV1ToV2RequireVersionMigration()
    private val ctx = MigrationContext(sourceVersion = 1, targetVersion = 2)

    private fun detail(json: String): ObjectNode = mapper.readTree(json.trimIndent()) as ObjectNode

    /** A v1 stencil detail: no `version` field on the resource. */
    private fun v1StencilDetail(): ObjectNode = detail(
        """
        {
          "schemaVersion": 1,
          "resource": {
            "type": "stencil",
            "slug": "company-header",
            "name": "Company Header",
            "tags": ["header"],
            "content": { "modelVersion": 1, "root": "n-root", "nodes": {} }
          }
        }
        """,
    )

    @Test
    fun `declares the stencil part, single-version advance v1 to v2`() {
        assertThat(migration.part).isEqualTo(CatalogPart.STENCIL)
        assertThat(migration.from).isEqualTo(1)
        assertThat(migration.to).isEqualTo(2)
    }

    @Test
    fun `assigns version 1 to a stencil that omits version`() {
        val result = migration.migrate(v1StencilDetail(), ctx)
        assertThat(result.at("/resource/version").asInt()).isEqualTo(1)
    }

    @Test
    fun `preserves all other stencil fields`() {
        val resource = migration.migrate(v1StencilDetail(), ctx).get("resource") as ObjectNode
        assertThat(resource.get("slug").asString()).isEqualTo("company-header")
        assertThat(resource.get("name").asString()).isEqualTo("Company Header")
        assertThat(resource.get("tags")[0].asString()).isEqualTo("header")
        assertThat(resource.at("/content/root").asString()).isEqualTo("n-root")
    }

    @Test
    fun `leaves an existing version untouched (idempotent on current-shape content)`() {
        val current = detail(
            """
            {
              "schemaVersion": 2,
              "resource": { "type": "stencil", "slug": "s", "name": "S", "version": 7, "content": {} }
            }
            """,
        )
        assertThat(migration.migrate(current, ctx).at("/resource/version").asInt()).isEqualTo(7)
    }

    @Test
    fun `re-running on an already-migrated detail is a no-op`() {
        val once = migration.migrate(v1StencilDetail(), ctx)
        val twice = migration.migrate(once.deepCopy(), ctx)
        assertThat(twice).isEqualTo(once)
    }
}
