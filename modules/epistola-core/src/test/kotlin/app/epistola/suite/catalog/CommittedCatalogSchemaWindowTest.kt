package app.epistola.suite.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Guard against out-of-window committed catalog fixtures.
 *
 * Every committed catalog wire file we ship or test with — the bundled
 * demo/system catalogs and the `test-catalogs` fixtures — must carry a
 * `schemaVersion` **inside its part's `[baseline, current]` window**
 * ([CATALOG_PART_SCHEMAS]). A stamp *below* a part's `baseline` imports today
 * only because of the empty-chain leniency in
 * [app.epistola.suite.catalog.migrations.CatalogSchemaMigrator] (sub-current +
 * empty chain → pass through); the moment that part gets a real migration chain
 * the same fixture would be rejected as `TooOld`. This test makes that latent
 * trap a build failure instead: our own committed content never relies on the
 * leniency crutch — only genuinely-old *external* payloads do.
 *
 * Part is read from the file itself (`resource.type`, or the manifest when there
 * is no `resource`), so it does not depend on the on-disk directory name.
 */
class CommittedCatalogSchemaWindowTest {

    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val resolver = PathMatchingResourcePatternResolver()

    @Test
    fun `every committed catalog wire file is within its part's schema-version window`() {
        val patterns = listOf(
            "classpath*:epistola/catalogs/**/*.json",
            "classpath*:test-catalogs/**/*.json",
        )
        val files = patterns.flatMap { resolver.getResources(it).toList() }
            .filter { it.isReadable }
            .distinctBy { it.uri.toString() }

        assertThat(files).describedAs("should find committed catalog wire files on the classpath").isNotEmpty

        val violations = files.mapNotNull { res ->
            val tree = res.inputStream.use { mapper.readTree(it) } as? ObjectNode ?: return@mapNotNull null
            val schemaVersionNode = tree.get("schemaVersion")
                ?: return@mapNotNull null // no schemaVersion → not a versioned wire file (e.g. component registry) — skip
            if (!schemaVersionNode.isIntegralNumber) {
                // Present but non-integer: a malformed wire stamp the runtime would
                // reject as CatalogSchemaUnknownException — never let it slip through.
                return@mapNotNull "${res.uri}: schemaVersion=$schemaVersionNode is not an integer"
            }
            val schemaVersion = schemaVersionNode.asInt()

            val resourceType = (tree.get("resource") as? ObjectNode)?.get("type")?.asString()
            val part = if (resourceType == null) {
                CatalogPart.MANIFEST
            } else {
                CatalogPart.ofResourceType(resourceType)
                    ?: return@mapNotNull "${res.filename}: unknown resource type '$resourceType'"
            }

            val window = CATALOG_PART_SCHEMAS.getValue(part)
            if (schemaVersion in window.baseline..window.current) {
                null
            } else {
                "${res.uri}: $part schemaVersion=$schemaVersion is outside window " +
                    "[${window.baseline}, ${window.current}]"
            }
        }

        assertThat(violations)
            .describedAs(
                "Committed catalog files must sit within their part's [baseline, current] window — " +
                    "a sub-baseline stamp only imports via the transitional empty-chain leniency and " +
                    "would break once that part gets a migration chain. Normalise the file to its part's " +
                    "current (its content is already current-shape), or widen the part's window in " +
                    "CATALOG_PART_SCHEMAS with a real migration.",
            )
            .isEmpty()
    }
}
