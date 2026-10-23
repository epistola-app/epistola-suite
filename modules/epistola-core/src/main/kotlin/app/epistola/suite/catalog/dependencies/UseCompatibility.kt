// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.releases.ChangeKindEvaluator
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Whether another version of a catalog fits what a catalog uses of it (contract ADR 0002, amendment
 * 2): compatibility is judged per use, not by the version number. The two published versions are
 * compared resource by resource, by the rules that classify a release's changes, and only the
 * breaking changes to resources that are used count. A new major that only breaks what is not used
 * fits; a patch that breaks something used does not.
 */
@Component
class UseCompatibility(
    private val releases: ReleaseContentAssembler,
    objectMapper: ObjectMapper,
) {
    private val changes = ChangeKindEvaluator(objectMapper)

    /**
     * What version [to] of [catalogKey] breaks, against version [from], of what is [used]. Empty when it
     * fits, and when either version is not here to compare.
     */
    fun breaks(tenantKey: TenantKey, catalogKey: CatalogKey, from: String, to: String, used: List<BorrowedResource>): List<UseBreak> {
        if (used.isEmpty() || from == to) return emptyList()
        val before = releases.assemble(tenantKey, catalogKey, from)?.content ?: return emptyList()
        val after = releases.assemble(tenantKey, catalogKey, to)?.content ?: return emptyList()
        return changes.evaluate(before, after).reasons
            .filter { it.kind == ChangeKind.BREAKING }
            .mapNotNull { reason ->
                used.firstOrNull { it.type == reason.type && it.key == reason.key }?.let { UseBreak(it.type, it.key, it.name, reason.reason) }
            }
    }

    companion object {
        /** What [refs] (a catalog's references into other catalogs) use of catalog [catalogKey]. */
        fun usedOf(refs: List<DependencyRef>, catalogKey: String): List<BorrowedResource> = refs
            .filter { it.catalogKey == catalogKey }
            .map {
                val type = when (it) {
                    is DependencyRef.Theme -> "theme"
                    is DependencyRef.Stencil -> "stencil"
                    is DependencyRef.Image -> "image"
                    is DependencyRef.CodeList -> "codeList"
                    is DependencyRef.Font -> "font"
                }
                BorrowedResource(type, it.key, null)
            }
            .distinct()
    }
}
