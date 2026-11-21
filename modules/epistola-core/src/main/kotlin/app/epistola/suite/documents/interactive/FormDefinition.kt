// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.documents.model.DocumentGenerationRequest
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * What the form page renders: a definition of the form, never the data contract directly
 * (interactive generation plan, decision 8). Today every form is generated from the contract of the
 * release the request is bound to. A form an author configures (sections, order, labels, help text)
 * becomes another kind here, resolved from the same release, without the job, the link or the API
 * changing.
 *
 * Whatever the form shows, the data contract alone decides validity: submissions and previews are
 * validated against it, so a form can never make a request impossible to submit.
 */
sealed interface FormDefinition {
    /** The kind the form page switches on. */
    val kind: String

    /** Every field of the data contract, in its order, as its schema describes it. */
    data class Generated(val schema: ObjectNode?) : FormDefinition {
        override val kind: String get() = "generated"
    }
}

/** Resolves the form a request's page shows. One kind today; see [FormDefinition]. */
@Component
class FormDefinitionResolver {
    fun resolve(@Suppress("UNUSED_PARAMETER") request: DocumentGenerationRequest, contract: BoundContract): FormDefinition = FormDefinition.Generated(contract.dataModel)
}
