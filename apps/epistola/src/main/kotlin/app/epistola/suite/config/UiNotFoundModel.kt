// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.security.TenantAccessDeniedException
import app.epistola.suite.tenants.queries.GetTenant

/**
 * Builds the shell model for a not-found page.
 *
 * A tenant from the requested path is used only when it still exists and is visible to the
 * current principal. Guessed, deleted, and inaccessible tenant paths therefore use the generic
 * application shell.
 */
internal fun notFoundPageModel(requestPath: String): Map<String, Any?> {
    val tenant = tenantKeyFromPath(requestPath)?.let {
        try {
            GetTenant(it).query()
        } catch (_: TenantAccessDeniedException) {
            null
        }
    }

    return buildMap {
        put("contentView", "error/404")
        put("pageTitle", "Page Not Found - Epistola")
        if (tenant != null) {
            put("tenantId", tenant.id)
            put("tenantName", tenant.name)
        }
    }
}

private fun tenantKeyFromPath(path: String): TenantKey? {
    val value = TENANT_PATH.find(path)?.groupValues?.get(1) ?: return null
    return TenantKey.validateOrNull(value)
}

private val TENANT_PATH = Regex("^/tenants/([^/]+)(?:/|$)")
