// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.catalog.dependencies.DraftDependencyViews
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.stencils.model.StencilInstances
import app.epistola.suite.stencils.model.describe
import app.epistola.suite.stencils.model.validationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Whether every stencil instance in a document fits the stencil as it is now (contract ADR 0006):
 * a fill for a placeholder the stencil lost, an unbound required parameter, a missing stencil,
 * recursion or too deep nesting does not.
 */
@Component
class StencilFitCheck(
    private val stencilResolvers: StencilResolvers,
    private val dependencyViews: DraftDependencyViews,
) {
    /** @throws ValidationException naming every instance of [document] that does not fit. */
    fun requireInstancesFit(tenantKey: TenantKey, catalogKey: String, document: TemplateDocument, what: String) {
        val problems = StencilInstances.expand(document, catalogKey, stencilResolvers.workingCopies(tenantKey, dependencyViews.of(tenantKey, CatalogKey.of(catalogKey)))).findings.filter { it.blocking }
        if (problems.isEmpty()) return
        throw ValidationException(
            field = "templateModel",
            message = "$what: a stencil instance " + problems.joinToString("; ") { it.describe() },
            code = problems.validationCode(),
        )
    }
}
