package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GroupMembershipParser::class.java)

private const val EP_PREFIX = "ep_"

/** All known tenant role names (lowercase with hyphens, matching group convention). */
private val KNOWN_TENANT_ROLES = mapOf(
    "reader" to TenantRole.READER,
    "editor" to TenantRole.EDITOR,
    "generator" to TenantRole.GENERATOR,
    "manager" to TenantRole.MANAGER,
)

/** All known platform role names (lowercase with hyphens, matching group convention). */
private val KNOWN_PLATFORM_ROLES = mapOf(
    "tenant-manager" to PlatformRole.TENANT_MANAGER,
)

/**
 * Result of parsing Keycloak group memberships from a JWT `groups` claim.
 *
 * All Epistola groups use the `ep_` prefix. The naming convention:
 * - `ep_{tenant}_{role}` → per-tenant role (e.g., `ep_acme-corp_reader`)
 * - `ep_{role}` → global role applying to all tenants (e.g., `ep_reader`)
 * - `ep_{platform-role}` → platform role (e.g., `ep_tenant-manager`)
 *
 * Groups not starting with `ep_` or with unrecognized roles are silently ignored.
 */
data class ParsedGroupMemberships(
    val tenantRoles: Map<TenantKey, Set<TenantRole>>,
    val globalRoles: Set<TenantRole>,
    val platformRoles: Set<PlatformRole>,
)

/**
 * Parses group names (from a JWT `groups` claim) into structured memberships.
 *
 * Uses the [GroupMembershipParser] singleton for the actual parsing logic.
 */
fun parseGroupMemberships(groups: List<String>): ParsedGroupMemberships = GroupMembershipParser.parse(groups)

/**
 * Parser for Keycloak group memberships using the `ep_` prefix convention.
 *
 * Parsing rules (after stripping the `ep_` prefix):
 * - Contains `_` → split on **last** `_` → left = tenant key, right = role
 * - No `_` → either a global tenant role or a platform role
 *
 * This is unambiguous because tenant keys match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
 * (no underscores allowed), and roles use hyphens only.
 */
object GroupMembershipParser {

    fun parse(groups: List<String>): ParsedGroupMemberships {
        val tenantRoles = mutableMapOf<TenantKey, MutableSet<TenantRole>>()
        val globalRoles = mutableSetOf<TenantRole>()
        val platformRoles = mutableSetOf<PlatformRole>()

        for (group in groups) {
            if (!group.startsWith(EP_PREFIX)) continue

            val remainder = group.removePrefix(EP_PREFIX)
            if (remainder.isEmpty()) continue

            val lastUnderscore = remainder.lastIndexOf('_')

            if (lastUnderscore > 0) {
                // Pattern: {tenant}_{role}
                val tenantPart = remainder.substring(0, lastUnderscore)
                val rolePart = remainder.substring(lastUnderscore + 1)

                val role = KNOWN_TENANT_ROLES[rolePart]
                if (role == null) {
                    log.debug("Ignoring group '{}': unrecognized role '{}'", group, rolePart)
                    continue
                }

                val tenantKey = try {
                    TenantKey.of(tenantPart)
                } catch (e: IllegalArgumentException) {
                    log.debug("Ignoring group '{}': invalid tenant key '{}'", group, tenantPart)
                    continue
                }

                tenantRoles.getOrPut(tenantKey) { mutableSetOf() }.add(role)
            } else {
                // Pattern: {role} (no underscore) → global tenant role or platform role
                val tenantRole = KNOWN_TENANT_ROLES[remainder]
                if (tenantRole != null) {
                    globalRoles.add(tenantRole)
                    continue
                }

                val platformRole = KNOWN_PLATFORM_ROLES[remainder]
                if (platformRole != null) {
                    platformRoles.add(platformRole)
                    continue
                }

                log.debug("Ignoring group '{}': unrecognized role '{}'", group, remainder)
            }
        }

        return ParsedGroupMemberships(
            tenantRoles = tenantRoles.mapValues { it.value.toSet() },
            globalRoles = globalRoles.toSet(),
            platformRoles = platformRoles.toSet(),
        )
    }
}
