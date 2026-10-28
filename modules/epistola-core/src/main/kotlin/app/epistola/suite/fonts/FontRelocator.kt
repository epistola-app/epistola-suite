// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.catalog.relocation.ResourceRelocator
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/** Relocates fonts (ADR 0039): a font moves with its content renamed; its faces are its own binaries and go with it. */
@Component
class FontRelocator(private val fonts: FontStore) : ResourceRelocator {
    override val type = FontStore.FONT

    override fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean = fonts.move(handle, source, key, target, targetKey)
}
