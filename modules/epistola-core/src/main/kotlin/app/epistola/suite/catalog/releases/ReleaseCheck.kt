// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.common.ids.CatalogId

/**
 * A rule a draft's content must satisfy before it is released, contributed by the domain whose rule
 * it is (ADR 0039): releasing runs every one. Stencil instances fitting their stencil is one.
 */
fun interface ReleaseCheck {
    /** @throws app.epistola.suite.validation.ValidationException naming what does not satisfy the rule. */
    fun requireSatisfied(catalog: CatalogId, content: CatalogContent)
}
