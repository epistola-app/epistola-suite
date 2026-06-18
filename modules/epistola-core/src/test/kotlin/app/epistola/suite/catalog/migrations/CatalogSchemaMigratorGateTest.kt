package app.epistola.suite.catalog.migrations

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.migrate
import app.epistola.suite.catalog.migrations.steps.CatalogV3ToV4ExampleMigration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * The catalog-wide wire-version gate in [CatalogSchemaMigrator]: which versions
 * pass, which are rejected, and that an in-window chain actually transforms +
 * re-stamps the tree. Pure unit test; exercises the companion gate directly so
 * the too-old / chain-execution branches (not reachable with the live empty
 * chain) are still covered ahead of Phase 1.
 */
class CatalogSchemaMigratorGateTest {

    private val mapper = jsonMapper { addModule(kotlinModule()) }

    /** Runs a step's manifest transform — the gate behaves the same for details. */
    private val applyManifest: (CatalogSchemaMigration, ObjectNode, MigrationContext) -> ObjectNode =
        { step, node, ctx -> step.migrateManifest(node, ctx) }

    private fun manifest(schemaVersion: String): ObjectNode = mapper.readTree(
        """
        {
          "schemaVersion": $schemaVersion,
          "catalog": { "slug": "x", "name": "X" },
          "publisher": { "name": "P" },
          "release": { "version": "1.0.0" },
          "resources": []
        }
        """.trimIndent(),
    ) as ObjectNode

    /** Marks the tree as it passes through, so we can assert which steps ran. */
    private class TraceMigration(override val from: Int) : CatalogSchemaMigration {
        override fun migrateManifest(node: ObjectNode, ctx: MigrationContext): ObjectNode {
            val prior = node.get("trace")?.asString() ?: ""
            node.put("trace", "$prior$from-$to;")
            return node
        }
    }

    private fun chain(vararg froms: Int): Map<Int, CatalogSchemaMigration> = froms.associateWith { TraceMigration(it) }

    @Test
    fun `current version passes through unchanged`() {
        val tree = manifest("4")
        val result = migrate(tree, byFrom = emptyMap(), baseline = 4, current = 4, apply = applyManifest)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace")).isNull()
    }

    @Test
    fun `newer than current is rejected as too new`() {
        assertThatThrownBy {
            migrate(manifest("5"), byFrom = emptyMap(), baseline = 4, current = 4, apply = applyManifest)
        }
            .isInstanceOf(CatalogSchemaTooNewException::class.java)
            .hasMessageContaining("newer")
    }

    @Test
    fun `older than current with no chain passes through (transitional)`() {
        // Phase-0 leniency: no migrations defined, so a sub-current payload is
        // assumed already current-shape and binds as-is.
        val result = migrate(manifest("2"), byFrom = emptyMap(), baseline = 4, current = 4, apply = applyManifest)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(2)
    }

    @Test
    fun `older than baseline with a chain present is rejected as too old`() {
        // baseline 4, current 6, but the payload is v3 — below the floor.
        assertThatThrownBy {
            migrate(manifest("3"), byFrom = chain(4, 5), baseline = 4, current = 6, apply = applyManifest)
        }
            .isInstanceOf(CatalogSchemaTooOldException::class.java)
            .hasMessageContaining("predates")
    }

    @Test
    fun `an in-window payload runs every step in order and re-stamps to current`() {
        val result = migrate(manifest("1"), byFrom = chain(1, 2, 3), baseline = 1, current = 4, apply = applyManifest)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace").asString()).isEqualTo("1-2;2-3;3-4;")
    }

    @Test
    fun `a payload entering mid-window runs only the remaining steps`() {
        val result = migrate(manifest("3"), byFrom = chain(1, 2, 3), baseline = 1, current = 4, apply = applyManifest)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace").asString()).isEqualTo("3-4;")
    }

    @Test
    fun `missing schemaVersion is rejected as unknown`() {
        val noVersion = mapper.readTree("""{ "catalog": { "slug": "x", "name": "X" } }""") as ObjectNode
        assertThatThrownBy { migrate(noVersion, emptyMap(), 4, 4, applyManifest) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("missing")
    }

    @Test
    fun `non-integer schemaVersion is rejected as unknown`() {
        assertThatThrownBy { migrate(manifest("\"four\""), emptyMap(), 4, 4, applyManifest) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
        assertThatThrownBy { migrate(manifest("4.5"), emptyMap(), 4, 4, applyManifest) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
    }

    @Test
    fun `migrateAndBindManifest binds a current-version manifest end to end`() {
        val migrator = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration()))
        val bound = migrator.migrateAndBindManifest(mapper.writeValueAsBytes(manifest("4")))
        assertThat(bound.catalog.slug).isEqualTo("x")
        assertThat(bound.release.version).isEqualTo("1.0.0")
    }

    @Test
    fun `migrateAndBindManifest rejects a too-new payload before binding`() {
        val migrator = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration()))
        assertThatThrownBy { migrator.migrateAndBindManifest(mapper.writeValueAsBytes(manifest("9"))) }
            .isInstanceOf(CatalogSchemaTooNewException::class.java)
    }

    private fun attributeDetail(schemaVersion: String, type: String = "attribute", legacy: Boolean = false): ByteArray {
        // legacy = the v3 shape (uses `displayName`); otherwise current `name`.
        val nameField = if (legacy) """"displayName": "Country"""" else """"name": "Country""""
        return mapper.writeValueAsBytes(
            mapper.readTree(
                """
                {
                  "schemaVersion": $schemaVersion,
                  "resource": { "type": "$type", "slug": "country", $nameField }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `migrateAndBindResourceDetail binds a current-version detail whose type matches`() {
        val migrator = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration()))
        val bound = migrator.migrateAndBindResourceDetail("attribute", attributeDetail("4"))
        assertThat(bound.resource).isInstanceOf(AttributeResource::class.java)
    }

    @Test
    fun `migrateAndBindResourceDetail rejects a detail whose own type contradicts the manifest entry`() {
        val migrator = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration()))
        // Manifest entry says "theme", but the detail's own discriminator is "attribute".
        assertThatThrownBy { migrator.migrateAndBindResourceDetail("theme", attributeDetail("4")) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("declares type 'attribute'")
    }

    @Test
    fun `invalid JSON is rejected as schema-unknown, not a raw Jackson error`() {
        val migrator = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration()))
        val garbage = "{ not json".toByteArray()
        assertThatThrownBy { migrator.migrateAndBindManifest(garbage) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
        assertThatThrownBy { migrator.migrateAndBindResourceDetail("attribute", garbage) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
    }

    @Test
    fun `a non-object JSON payload is rejected as schema-unknown`() {
        val migrator = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration()))
        assertThatThrownBy { migrator.migrateAndBindManifest("[]".toByteArray()) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("not a JSON object")
    }

    @Test
    fun `a v3 manifest is migrated to v4 and binds (legacy title to name)`() {
        val migrator = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration()))
        val v3 = mapper.writeValueAsBytes(
            mapper.readTree(
                """
                {
                  "schemaVersion": 3,
                  "catalog": { "slug": "acme", "title": "Acme Templates" },
                  "publisher": { "name": "P" },
                  "release": { "version": "1.0.0" },
                  "resources": []
                }
                """.trimIndent(),
            ),
        )
        val bound = migrator.migrateAndBindManifest(v3)
        assertThat(bound.catalog.slug).isEqualTo("acme")
        assertThat(bound.catalog.name).isEqualTo("Acme Templates")
    }

    @Test
    fun `a v3 resource detail is migrated to v4 and binds (legacy displayName to name)`() {
        val migrator = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration()))
        val bound = migrator.migrateAndBindResourceDetail("attribute", attributeDetail("3", legacy = true))
        assertThat(bound.resource).isInstanceOf(AttributeResource::class.java)
        assertThat((bound.resource as AttributeResource).name).isEqualTo("Country")
    }
}
