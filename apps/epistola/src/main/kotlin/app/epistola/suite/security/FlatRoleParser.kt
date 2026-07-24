// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FlatRoleParser::class.java)

private const val PREFIX_GLOBAL = "epg_"
private const val PREFIX_TENANT = "ept_"
private const val PREFIX_PLATFORM = "eps_"

/**
 * Parses memberships from a flat string-array JWT claim using a prefix convention.
 *
 * The convention exists for IdPs that cannot emit hierarchical groups (Auth0, Cognito,
 * AD-federated setups). Each string in the claim is one of:
 * - `epg_<role>` — global tenant role (applies to all tenants)
 * - `ept_<tenantKey>_<role>` — per-tenant role
 * - `eps_<platformRole>` — platform role (e.g. `eps_tenant_manager`)
 *
 * The role-name vocabulary matches the Keycloak group convention but uses underscores
 * instead of hyphens (since `-` is allowed inside tenant keys). `_` is normalised to `-`
 * before role lookup, so `eps_tenant_manager` resolves to `PlatformRole.TENANT_MANAGER`.
 *
 * Strings not matching any prefix, or with unknown role names / invalid tenant keys, are
 * silently ignored — mirroring [GroupMembershipParser]'s lenient behaviour so unrelated
 * IdP roles don't break login.
 */
fun parseFlatRoles(roles: List<String>): ParsedMemberships = FlatRoleParser.parse(roles)

object FlatRoleParser {

    fun parse(roles: List<String>): ParsedMemberships {
        val tenantRoles = mutableMapOf<TenantKey, MutableSet<TenantRole>>()
        val globalRoles = mutableSetOf<TenantRole>()
        val platformRoles = mutableSetOf<PlatformRole>()

        for (raw in roles) {
            when {
                raw.startsWith(PREFIX_PLATFORM) -> parsePlatform(raw.removePrefix(PREFIX_PLATFORM), raw, platformRoles)
                raw.startsWith(PREFIX_GLOBAL) -> parseGlobal(raw.removePrefix(PREFIX_GLOBAL), raw, globalRoles)
                raw.startsWith(PREFIX_TENANT) -> parseTenant(raw.removePrefix(PREFIX_TENANT), raw, tenantRoles)
                else -> log.debug("Ignoring flat role '{}': no recognised prefix", raw)
            }
        }

        return ParsedMemberships(
            tenantRoles = tenantRoles.mapValues { it.value.toSet() },
            globalRoles = globalRoles.toSet(),
            platformRoles = platformRoles.toSet(),
        )
    }

    private fun parsePlatform(rest: String, raw: String, into: MutableSet<PlatformRole>) {
        val normalized = rest.replace('_', '-')
        val role = KNOWN_PLATFORM_ROLES[normalized]
        if (role != null) {
            into.add(role)
        } else {
            log.debug("Ignoring flat role '{}': unrecognised platform role '{}'", raw, rest)
        }
    }

    private fun parseGlobal(rest: String, raw: String, into: MutableSet<TenantRole>) {
        val role = KNOWN_TENANT_ROLES[rest]
        if (role != null) {
            into.add(role)
        } else {
            log.debug("Ignoring flat role '{}': unrecognised global role '{}'", raw, rest)
        }
    }

    private fun parseTenant(
        rest: String,
        raw: String,
        into: MutableMap<TenantKey, MutableSet<TenantRole>>,
    ) {
        val segments = rest.split('_')
        if (segments.size != 2) {
            log.debug("Ignoring flat role '{}': expected ept_<tenantKey>_<role>", raw)
            return
        }
        val (tenantPart, rolePart) = segments

        val role = KNOWN_TENANT_ROLES[rolePart]
        if (role == null) {
            log.debug("Ignoring flat role '{}': unrecognised tenant role '{}'", raw, rolePart)
            return
        }

        val tenantKey = try {
            TenantKey.of(tenantPart)
        } catch (e: IllegalArgumentException) {
            log.debug("Ignoring flat role '{}': invalid tenant key '{}'", raw, tenantPart)
            return
        }

        into.getOrPut(tenantKey) { mutableSetOf() }.add(role)
    }
}
