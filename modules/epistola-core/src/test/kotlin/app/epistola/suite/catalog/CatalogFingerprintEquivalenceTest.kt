// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ThemeResource
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals

/**
 * F3 invariant: the fingerprint stamped/stored for a catalog (the typed
 * `fingerprint(...)` path used by export & release) is **byte-equal** to what
 * a consumer recomputes from the serialized wire bytes
 * ([CatalogCanonicalizer.fingerprintFromSerializedDetails], the same pipeline
 * as [CatalogCanonicalizer.fingerprintFromSource]). Exercised with `Float`
 * (`spacingUnit`) and `Double` (`dataModel`) fields — precisely the values
 * that diverged before unification.
 */
class CatalogFingerprintEquivalenceTest {

    private val om = jsonMapper { addModule(kotlinModule()) }
    private val canon = CatalogCanonicalizer(om)
    private val catalog = CatalogInfo(slug = "demo", name = "Demo", description = "d")

    @Test
    fun `typed path equals recompute-from-serialized-wire (float and double bearing)`() {
        val details = linkedMapOf(
            "theme/corporate" to ResourceDetail(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                resource = ThemeResource(
                    slug = "corporate",
                    name = "Corporate",
                    spacingUnit = 4.5f,
                    documentStyles = mapOf("fontSize" to 12.0, "lineHeight" to 1.15),
                ),
            ),
            "asset/logo" to ResourceDetail(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                resource = AssetResource(slug = "logo", name = "Logo", mediaType = "image/png", contentUrl = "./resources/asset/logo"),
            ),
        )
        val assets = { contentUrl: String -> if (contentUrl.endsWith("logo")) "PNGBYTES".toByteArray() else null }

        val stamped = canon.fingerprint(catalog, details, null, assets)
        val recomputedFromWire = canon.fingerprintFromSerializedDetails(
            catalog,
            details.mapValues { (_, d) -> om.writeValueAsBytes(d) },
            null,
            assets,
        )

        assertEquals(recomputedFromWire, stamped, "stamped fingerprint must equal recompute-from-wire")
    }
}
