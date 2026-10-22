// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ThemeResource
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CatalogCanonicalizerTest {

    private val canonicalizer = CatalogCanonicalizer(jsonMapper { addModule(kotlinModule()) })

    private val catalog = CatalogInfo(key = "demo", name = "Demo", description = "d")

    private fun theme(slug: String, unit: Float) = ResourceDetail(
        schemaVersion = CATALOG_SCHEMA_VERSION,
        resource = ThemeResource(key = slug, name = slug, spacingUnit = unit),
    )

    private fun asset(slug: String) = ResourceDetail(
        schemaVersion = CATALOG_SCHEMA_VERSION,
        resource = ImageResource(key = slug, name = slug, mediaType = "image/png", contentUrl = "./resources/asset/$slug", contentHash = "0000000000000000000000000000000000000000000000000000000000000000"),
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
            DependencyRef.Font(catalogKey = "system", key = "inter"),
            DependencyRef.Theme(catalogKey = "shared", key = "base"),
        )
        val depsB = depsA.reversed()
        assertEquals(
            canonicalizer.fingerprint(catalog, details, depsA) { null },
            canonicalizer.fingerprint(catalog, details, depsB) { null },
        )
    }

    /**
     * V6, a release's content hash (ADR 0034): the catalog's name and presentation are not content,
     * so a catalog renamed, or presented otherwise, has the same fingerprint; its license is content.
     */
    @Test
    fun `fingerprint covers content, not the catalog's name or presentation`() {
        val details = linkedMapOf("theme/x" to theme("x", 4f))
        fun variant(name: String = catalog.name, key: String = catalog.key, license: app.epistola.catalog.protocol.CatalogLicense? = catalog.license) = CatalogInfo.create(
            key = key,
            name = name,
            description = "presented otherwise",
            attributes = catalog.attributes,
            keywords = setOf("other"),
            presentation = catalog.presentation,
            license = license,
        )
        val original = canonicalizer.fingerprint(catalog, details, null) { null }
        assertEquals(original, canonicalizer.fingerprint(variant(name = "Renamed"), details, null) { null })
        assertEquals(original, canonicalizer.fingerprint(variant(key = "other-key"), details, null) { null })
        assertNotEquals(original, canonicalizer.fingerprint(variant(license = app.epistola.catalog.protocol.CatalogLicense("MIT")), details, null) { null })
    }

    @Test
    fun `per-resource fingerprints are stable, isolate change, and fold asset bytes`() {
        val v1 = linkedMapOf("theme/x" to theme("x", 4f), "asset/logo" to asset("logo"))
        val fp1 = canonicalizer.perResourceFingerprints(v1) { "AAA".toByteArray() }
        val fp1Reordered = canonicalizer.perResourceFingerprints(
            linkedMapOf("asset/logo" to asset("logo"), "theme/x" to theme("x", 4f)),
        ) { "AAA".toByteArray() }
        assertEquals(fp1, fp1Reordered, "order-independent and deterministic")
        assertEquals(setOf("theme/x", "asset/logo"), fp1.keys)

        // Change only the theme → only theme/x digest changes.
        val v2 = linkedMapOf("theme/x" to theme("x", 8f), "asset/logo" to asset("logo"))
        val fp2 = canonicalizer.perResourceFingerprints(v2) { "AAA".toByteArray() }
        assertNotEquals(fp1["theme/x"], fp2["theme/x"])
        assertEquals(fp1["asset/logo"], fp2["asset/logo"])

        // Swapped asset bytes → only the asset digest changes.
        val fp3 = canonicalizer.perResourceFingerprints(v1) { "BBB".toByteArray() }
        assertNotEquals(fp1["asset/logo"], fp3["asset/logo"])
        assertEquals(fp1["theme/x"], fp3["theme/x"])
    }
}
