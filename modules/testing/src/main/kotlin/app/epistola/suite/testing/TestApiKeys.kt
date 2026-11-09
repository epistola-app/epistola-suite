// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.apikeys.ApiKeyWithSecret
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.TenantRole
import java.time.Instant

/**
 * An API key for a test: a new application of [tenant] named [name], acting with [roles] and seeing
 * every document unless told otherwise, and one key on it (ADR 0054). A key has no roles of its own,
 * so a test that needs a key with certain roles needs an application with them; this composes the
 * two commands. Call it inside a mediator scope.
 */
fun createApiKey(
    tenant: TenantKey,
    name: String,
    roles: Set<TenantRole> = TenantRole.entries.toSet(),
    seesAllDocuments: Boolean = true,
    expiresAt: Instant? = null,
): ApiKeyWithSecret {
    val application = CreateApplication(tenantKey = tenant, name = name, roles = roles, seesAllDocuments = seesAllDocuments).execute()
    return CreateApiKey(tenantId = tenant, name = name, applicationId = application.id, expiresAt = expiresAt).execute()
}
