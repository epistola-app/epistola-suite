// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * Where a form may send the browser, and link to (contract ADR 0011, "Coming back"). A page on
 * Epistola's domain that forwards wherever a leaked key chooses would be an open redirect, so every
 * `returnUrl` and link must start with an allowed [ReturnUrlPrefix]:
 *
 * - the submitting application's own `allowed_return_urls`, when the caller has an application
 *   (ADR 0054): where its frontend lives, so a leaked key can only send a browser back to its system;
 * - the installation's `epistola.generation.interactive.allowed-origins`, for a caller without one
 *   (a person in the web UI, a bearer token, the demo secret);
 * - always this installation's own origin, which the web UI's "Generate document" returns to.
 */
@Component
class ReturnUrlPolicy(private val properties: InteractiveGenerationProperties) {

    /** Refuses the first URL of [interactive] that starts with no allowed prefix, with `url-not-allowed`. */
    fun check(handle: Handle, tenantKey: TenantKey, application: ApplicationKey?, interactive: InteractiveGeneration) {
        val urls = interactive.urls()
        if (urls.isEmpty()) return
        val allowed = allowedPrefixes(handle, tenantKey, application)
        urls.forEach { (field, url) ->
            if (allowed.none { it.matches(url) }) {
                throw ValidationException(field, "$url is not an allowed address", ValidationCode.URL_NOT_ALLOWED)
            }
        }
    }

    private fun allowedPrefixes(handle: Handle, tenantKey: TenantKey, application: ApplicationKey?): List<ReturnUrlPrefix> {
        val configured = if (application == null) {
            properties.normalisedAllowedOrigins.toList()
        } else {
            handle.createQuery("SELECT allowed_return_urls FROM applications WHERE tenant_key = :tenantKey AND id = :id")
                .bind("tenantKey", tenantKey)
                .bind("id", application)
                .mapTo(Array<String>::class.java)
                .findOne()
                .orElse(emptyArray())
                .toList()
        }
        return (configured + listOfNotNull(properties.ownOrigin)).mapNotNull(ReturnUrlPrefix::parse)
    }
}
