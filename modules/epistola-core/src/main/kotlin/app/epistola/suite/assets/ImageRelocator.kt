// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.catalog.relocation.ResourceRelocator
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/** Relocates images (ADR 0039): an image moves with its content renamed; its bytes are its own binary and go with it. */
@Component
class ImageRelocator(private val images: ImageStore) : ResourceRelocator {
    override val type = ImageStore.IMAGE

    override fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean = images.move(handle, source, key, target, targetKey)
}
