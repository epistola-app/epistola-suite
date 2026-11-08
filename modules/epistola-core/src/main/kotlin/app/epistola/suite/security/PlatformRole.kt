// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

/**
 * Platform-scoped roles that apply across all tenants.
 *
 * These are sourced from Keycloak **groups** using hierarchical group paths.
 * For example, `/epistola/platform/tenant-manager` in the `groups` JWT claim maps to [TENANT_MANAGER].
 */
enum class PlatformRole {
    /**
     * Can create and manage tenants across the platform.
     * This is a cross-tenant role — fundamentally different from per-tenant MANAGER.
     */
    TENANT_MANAGER,

    /**
     * Installation state: cluster maintenance, the site banner, and what the installation shares
     * with Epistola. Not tenants, which stay [TENANT_MANAGER]'s (ADR 0048).
     */
    INSTALLATION_ADMINISTRATOR,

    /**
     * Cross-tenant **read-only** access for operations and support staff: diagnostics and the
     * audit trail in every tenant ([permissionsInEveryTenant]). Grants no write capability.
     */
    PLATFORM_OBSERVER,

    /**
     * Works the installation's support desk (ADR 0053): the support requests of every tenant, their
     * replies and internal notes, their status and priority, and passing them on to a support
     * connection. Grants nothing in any tenant.
     */
    SUPPORT_DESK,
}
