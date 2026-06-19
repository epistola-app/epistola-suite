package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.migrateContentBlobTree
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/**
 * Unit checks for the at-rest content-blob gate + chain run
 * ([CatalogSchemaMigrator.migrateContentBlobTree]). Mirrors the manifest gate but
 * the version comes from the carrier's `schema_version` column (passed in), and
 * the blob is never version-stamped. Pure; no Spring, no DB.
 */
class CatalogSchemaMigratorContentBlobTest {

    private val mapper = jsonMapper()
    private fun obj(): ObjectNode = mapper.createObjectNode()

    /** Marks a blob with `step_<from>`, optionally only for one [ContentBlobType]. */
    private class MarkMigration(override val from: Int, private val onlyType: String? = null) : CatalogSchemaMigration {
        override fun migrateContentBlob(blobType: String, blob: JsonNode, ctx: MigrationContext): JsonNode {
            if (onlyType != null && blobType != onlyType) return blob
            return (blob as ObjectNode).put("step_$from", true)
        }
    }

    private fun chain(vararg froms: Int) = froms.map { MarkMigration(it) }.associateBy { it.from }

    @Test
    fun `current version is identity`() {
        val node = obj().put("x", 1)
        val result = migrateContentBlobTree(ContentBlobType.TEMPLATE_DOCUMENT, node, sourceVersion = 4, byFrom = chain(), baseline = 4, current = 4)
        assertThat(result).isSameAs(node)
    }

    @Test
    fun `empty chain below current is identity (transitional)`() {
        val node = obj().put("x", 1)
        val result = migrateContentBlobTree(ContentBlobType.TEMPLATE_DOCUMENT, node, sourceVersion = 1, byFrom = emptyMap(), baseline = 1, current = 4)
        assertThat(result).isSameAs(node)
    }

    @Test
    fun `newer than current is rejected as too new`() {
        assertThatThrownBy {
            migrateContentBlobTree(ContentBlobType.TEMPLATE_DOCUMENT, obj(), sourceVersion = 5, byFrom = chain(), baseline = 4, current = 4)
        }.isInstanceOf(CatalogSchemaTooNewException::class.java)
    }

    @Test
    fun `older than baseline with a chain is rejected as too old`() {
        assertThatThrownBy {
            migrateContentBlobTree(ContentBlobType.TEMPLATE_DOCUMENT, obj(), sourceVersion = 1, byFrom = chain(2, 3), baseline = 2, current = 4)
        }.isInstanceOf(CatalogSchemaTooOldException::class.java)
    }

    @Test
    fun `runs every step in order from source to current`() {
        val result = migrateContentBlobTree(ContentBlobType.TEMPLATE_DOCUMENT, obj(), sourceVersion = 1, byFrom = chain(1, 2, 3), baseline = 1, current = 4) as ObjectNode
        assertThat(result.has("step_1")).isTrue()
        assertThat(result.has("step_2")).isTrue()
        assertThat(result.has("step_3")).isTrue()
    }

    @Test
    fun `entering mid-window runs only the remaining steps`() {
        val result = migrateContentBlobTree(ContentBlobType.TEMPLATE_DOCUMENT, obj(), sourceVersion = 3, byFrom = chain(1, 2, 3), baseline = 1, current = 4) as ObjectNode
        assertThat(result.has("step_1")).isFalse()
        assertThat(result.has("step_2")).isFalse()
        assertThat(result.has("step_3")).isTrue()
    }

    @Test
    fun `a step branches on blob type`() {
        val byFrom = listOf(MarkMigration(from = 1, onlyType = ContentBlobType.TEMPLATE_DOCUMENT)).associateBy { it.from }
        val styles = migrateContentBlobTree(ContentBlobType.DOCUMENT_STYLES, obj(), sourceVersion = 1, byFrom = byFrom, baseline = 1, current = 2) as ObjectNode
        val doc = migrateContentBlobTree(ContentBlobType.TEMPLATE_DOCUMENT, obj(), sourceVersion = 1, byFrom = byFrom, baseline = 1, current = 2) as ObjectNode
        assertThat(styles.has("step_1")).isFalse()
        assertThat(doc.has("step_1")).isTrue()
    }

    @Test
    fun `does not stamp a version into the blob`() {
        val result = migrateContentBlobTree(ContentBlobType.TEMPLATE_DOCUMENT, obj(), sourceVersion = 1, byFrom = chain(1, 2, 3), baseline = 1, current = 4) as ObjectNode
        assertThat(result.has("schemaVersion")).isFalse()
    }
}
