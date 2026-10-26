// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.FontVariantEntry
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.suite.fonts.model.sha256Hex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Which binaries the fingerprints and the validator read. A content's font faces can be megabytes
 * (the system catalog's are 14 MB) and are loaded from the database on demand, while the fingerprints
 * fold in only an image's bytes; a font counts by the hashes its faces declare. Reading every face for
 * every fingerprint made each new tenant load the system fonts three times.
 */
class CatalogCanonicalizerBinaryReadsTest {

    private val canonicalizer = CatalogCanonicalizer(jsonMapper { addModule(kotlinModule()) })

    private val image = byteArrayOf(1, 2, 3)
    private val regular = ByteArray(1024) { 4 }
    private val bold = ByteArray(1024) { 5 }

    private val imageResource = ImageResource(key = "logo", name = "Logo", mediaType = "image/png", contentUrl = "./binaries/logo.png", contentHash = sha256Hex(image))
    private val fontResource = FontResource(
        key = "brand",
        name = "Brand",
        kind = "sans",
        variants = listOf(
            FontVariantEntry(400, false, "font/ttf", sha256Hex(regular), "./binaries/brand-Regular.ttf"),
            FontVariantEntry(700, false, "font/ttf", sha256Hex(bold), "./binaries/brand-Bold.ttf"),
        ),
    )

    /** The binaries by path, recording every one that is read. */
    private class CountingBinaries(private val bytes: Map<String, ByteArray>) : AbstractMap<String, ByteArray>() {
        val read = mutableListOf<String>()

        override fun get(key: String): ByteArray? = bytes[key]?.also { read += key }

        override fun containsKey(key: String): Boolean = key in bytes

        override val entries: Set<Map.Entry<String, ByteArray>> get() = error("the binaries are read by path, never listed")
    }

    private val binaries = CountingBinaries(
        mapOf(
            imageResource.contentPath() to image,
            fontResource.variants[0].contentPath() to regular,
            fontResource.variants[1].contentPath() to bold,
        ),
    )

    private val content = CatalogContent(
        catalog = CatalogInfo(key = "brand", name = "Brand").withNamespace(namespace = "acme", registry = "exchange.epistola.app"),
        resourceEntries = listOf(
            ResourceEntry(type = "image", key = "logo", name = "Logo", detailUrl = "./resources/image/logo.json"),
            ResourceEntry(type = "font", key = "brand", name = "Brand", detailUrl = "./resources/font/brand.json"),
        ),
        resourceDetails = mapOf(
            "image/logo" to ResourceDetail(CATALOG_SCHEMA_VERSION, imageResource),
            "font/brand" to ResourceDetail(CATALOG_SCHEMA_VERSION, fontResource),
        ),
        dependencies = null,
        assetContents = binaries,
    )

    private val faces = fontResource.variants.map { it.contentPath() }

    @Test
    fun `the fingerprints read an image's bytes and no font face`() {
        val fingerprint = canonicalizer.fingerprint(content)
        canonicalizer.contentHash(content)
        canonicalizer.completePerResourceFingerprints(content)
        canonicalizer.matchesFingerprint(content, fingerprint)
        canonicalizer.perResourceFingerprints(content.resourceDetails, hasAsset = binaries::containsKey) { binaries[it] }

        assertThat(binaries.read).doesNotContainAnyElementsOf(faces)
        assertThat(binaries.read).contains(imageResource.contentPath())
    }

    @Test
    fun `the validator still checks every face against its declared hash, reading each once`() {
        val report = canonicalizer.publishability(content)

        assertThat(report.valid).`as`(report.findings.toString()).isTrue()
        assertThat(binaries.read.filter { it in faces }).containsExactlyInAnyOrderElementsOf(faces)
    }

    @Test
    fun `a face missing from the content is still reported missing`() {
        val missingBold = content.copy(assetContents = CountingBinaries(mapOf(imageResource.contentPath() to image, faces[0] to regular)))

        assertThat(canonicalizer.publishability(missingBold).findings.map { it.message }).anySatisfy { assertThat(it).contains("brand-Bold.ttf").contains("missing") }
    }
}
