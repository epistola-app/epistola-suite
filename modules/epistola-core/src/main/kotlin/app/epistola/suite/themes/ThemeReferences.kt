// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.DependencyScanner
import tools.jackson.databind.ObjectMapper

/** The font references of this theme into other catalogs: its document styles' and its block-style presets'. */
@Suppress("UNCHECKED_CAST")
fun Theme.crossCatalogRefs(objectMapper: ObjectMapper): Set<DependencyRef> {
    val presets = blockStylePresets?.let { objectMapper.convertValue(it, Map::class.java) as Map<String, Any?> }
        ?.mapValues { (_, preset) -> (preset as? Map<String, Any?>)?.get("styles") ?: preset }
    return DependencyScanner.themeCrossCatalogRefs(documentStyles, presets, catalogKey.value)
}
