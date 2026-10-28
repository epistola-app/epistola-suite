// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.catalog.relocation.ResourceRelocator
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/** Relocates code lists (ADR 0039): a code list moves with its content renamed. */
@Component
class CodeListRelocator(private val codeLists: CodeListStore) : ResourceRelocator {
    override val type = CodeListStore.CODE_LIST

    override fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean = codeLists.move(handle, source, key, target, targetKey)
}
