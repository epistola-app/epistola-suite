// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.documents.interactive.FormSession
import app.epistola.suite.documents.interactive.InteractiveFormLinks
import app.epistola.suite.documents.interactive.InteractiveForms
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * A person opens a link to a form (epistola-contract#134): when [code] is a link to [requestId]'s
 * form that has not expired and was not opened before, it is redeemed and a session on the form
 * starts. Null otherwise: an unknown request, a request without a form, a forged, expired or used
 * code all look the same.
 *
 * [SystemInternal]: the person has no Epistola account. The code, which the caller that created
 * the request handed them, is the authority, over this one form and nothing else.
 */
data class RedeemFormLink(val requestId: GenerationRequestKey, val code: String) :
    Command<FormSession?>,
    SystemInternal {
    override fun toString(): String = "RedeemFormLink(requestId=$requestId, code=***)"
}

@Component
class RedeemFormLinkHandler(
    private val jdbi: Jdbi,
    private val forms: InteractiveForms,
    private val links: InteractiveFormLinks,
) : CommandHandler<RedeemFormLink, FormSession?> {
    override fun handle(command: RedeemFormLink): FormSession? = jdbi.inTransaction<FormSession?, Exception> { handle ->
        val form = forms.find(handle, command.requestId) ?: return@inTransaction null
        if (!links.redeem(handle, command.requestId, command.code)) return@inTransaction null
        links.openSession(command.requestId, form.expiresAt)
    }
}
