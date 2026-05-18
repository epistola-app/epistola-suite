package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ThemeResource
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CatalogCanonicalizerTest {

    private val canonicalizer = CatalogCanonicalizer(jsonMapper { addModule(kotlinModule()) })

    private val catalog = CatalogInfo(slug = "demo", name = "Demo", description = "d")

    private fun theme(slug: String, unit: Float) = ResourceDetail(
        schemaVersion = CATALOG_MANIFEST_SCHEMA_VERSION,
        resource = ThemeResource(slug = slug, name = slug, spacingUnit = unit),
    )

    private fun asset(slug: String) = ResourceDetail(
        schemaVersion = CATALOG_MANIFEST_SCHEMA_VERSION,
        resource = AssetResource(slug = slug, name = slug, mediaType = "image/png", contentUrl = "./resources/asset/$slug"),
    )

    @Test
    fun `fingerprint is independent of resource iteration order`() {
        val a = linkedMapOf("theme/x" to theme("x", 4f), "theme/y" to theme("y", 8f))
        val b = linkedMapOf("theme/y" to theme("y", 8f), "theme/x" to theme("x", 4f))
        assertEquals(
            canonicalizer.fingerprint(catalog, a, null) { null },
            canonicalizer.fingerprint(catalog, b, null) { null },
        )
    }

    @Test
    fun `fingerprint changes when a resource body changes`() {
        val a = linkedMapOf("theme/x" to theme("x", 4f))
        val b = linkedMapOf("theme/x" to theme("x", 8f))
        assertNotEquals(
            canonicalizer.fingerprint(catalog, a, null) { null },
            canonicalizer.fingerprint(catalog, b, null) { null },
        )
    }

    @Test
    fun `fingerprint folds in asset bytes`() {
        val details = linkedMapOf("asset/logo" to asset("logo"))
        val one = canonicalizer.fingerprint(catalog, details, null) { "AAA".toByteArray() }
        val two = canonicalizer.fingerprint(catalog, details, null) { "BBB".toByteArray() }
        assertNotEquals(one, two, "swapping asset bytes must flip the fingerprint")
    }

    @Test
    fun `fingerprint is independent of dependency order`() {
        val details = linkedMapOf("theme/x" to theme("x", 4f))
        val depsA = listOf(
            DependencyRef.Font(catalogKey = "system", slug = "inter"),
            DependencyRef.Theme(catalogKey = "shared", slug = "base"),
        )
        val depsB = depsA.reversed()
        assertEquals(
            canonicalizer.fingerprint(catalog, details, depsA) { null },
            canonicalizer.fingerprint(catalog, details, depsB) { null },
        )
    }

    @Test
    fun `fingerprint changes when catalog identity changes`() {
        val details = linkedMapOf("theme/x" to theme("x", 4f))
        assertNotEquals(
            canonicalizer.fingerprint(catalog, details, null) { null },
            canonicalizer.fingerprint(catalog.copy(name = "Renamed"), details, null) { null },
        )
    }
}
