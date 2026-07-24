// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
 * demo/system catalogs and the `test-catalogs` fixtures, manifest and resource
 * details alike — must carry a `schemaVersion` **inside the catalog-wide
 * `[CATALOG_BASELINE_SCHEMA_VERSION, CATALOG_SCHEMA_VERSION]` window**. A stamp
 * *below* baseline imports today only because of the empty-chain leniency in
 * [app.epistola.suite.catalog.migrations.CatalogSchemaMigrator] (sub-current +
 * empty chain → pass through); the moment a migration chain exists the same
 * fixture would be rejected as `TooOld`. This test makes that latent trap a build
 * failure: our own committed content never relies on the leniency crutch — only
 * genuinely-old *external* payloads do.
 */
class CommittedCatalogSchemaWindowTest {

    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val resolver = PathMatchingResourcePatternResolver()

    @Test
    fun `every committed catalog wire file is within the catalog schema-version window`() {
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
            if (schemaVersion in CATALOG_BASELINE_SCHEMA_VERSION..CATALOG_SCHEMA_VERSION) {
                null
            } else {
                "${res.uri}: schemaVersion=$schemaVersion is outside the catalog window " +
                    "[$CATALOG_BASELINE_SCHEMA_VERSION, $CATALOG_SCHEMA_VERSION]"
            }
        }

        assertThat(violations)
            .describedAs(
                "Committed catalog files must sit within the catalog [baseline, current] window — " +
                    "a sub-baseline stamp only imports via the transitional empty-chain leniency and " +
                    "would break once a migration chain exists. Normalise the file to the current " +
                    "version (its content is already current-shape), or widen the window in " +
                    "CatalogConstants with a real migration.",
            )
            .isEmpty()
    }
}
