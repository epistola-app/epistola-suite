package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GroupMembershipParser::class.java)

private const val ROOT_SEGMENT = "epistola"
private const val TENANTS_SEGMENT = "tenants"
private const val GLOBAL_SEGMENT = "global"
private const val PLATFORM_SEGMENT = "platform"

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
 * All Epistola groups live under the `/epistola` root group in Keycloak. The path convention:
 * - `/epistola/tenants/{tenant}/{role}` → per-tenant role (e.g., `/epistola/tenants/acme-corp/reader`)
 * - `/epistola/global/{role}` → global role applying to all tenants (e.g., `/epistola/global/reader`)
 * - `/epistola/platform/{role}` → platform role (e.g., `/epistola/platform/tenant-manager`)
 *
 * Groups not matching these patterns are silently ignored.
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
 * Parser for Keycloak hierarchical group memberships using path-based convention.
 *
 * Parsing rules (splitting the group path on `/`):
 * - `/epistola/tenants/{tenant}/{role}` → per-tenant role
 * - `/epistola/global/{role}` → global tenant role (applies to all tenants)
 * - `/epistola/platform/{role}` → platform role
 *
 * This is unambiguous because the path structure encodes the category explicitly.
 * Tenant keys match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` and cannot collide with
 * the reserved segments `tenants`, `global`, or `platform`.
 */
object GroupMembershipParser {

    fun parse(groups: List<String>): ParsedGroupMemberships {
        val tenantRoles = mutableMapOf<TenantKey, MutableSet<TenantRole>>()
        val globalRoles = mutableSetOf<TenantRole>()
        val platformRoles = mutableSetOf<PlatformRole>()

        for (group in groups) {
            // Split "/epistola/tenants/demo/reader" → ["", "epistola", "tenants", "demo", "reader"]
            val segments = group.split('/')

            // Must start with "/" (empty first segment) and have "epistola" as root
            if (segments.size < 3 || segments[0].isNotEmpty() || segments[1] != ROOT_SEGMENT) continue

            when (segments[2]) {
                TENANTS_SEGMENT -> {
                    // /epistola/tenants/{tenant}/{role} → 5 segments
                    if (segments.size != 5) {
                        log.debug("Ignoring group '{}': expected /epistola/tenants/{{tenant}}/{{role}}", group)
                        continue
                    }
                    val tenantPart = segments[3]
                    val rolePart = segments[4]

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
                }

                GLOBAL_SEGMENT -> {
                    // /epistola/global/{role} → 4 segments
                    if (segments.size != 4) {
                        log.debug("Ignoring group '{}': expected /epistola/global/{{role}}", group)
                        continue
                    }
                    val rolePart = segments[3]

                    val tenantRole = KNOWN_TENANT_ROLES[rolePart]
                    if (tenantRole != null) {
                        globalRoles.add(tenantRole)
                    } else {
                        log.debug("Ignoring group '{}': unrecognized global role '{}'", group, rolePart)
                    }
                }

                PLATFORM_SEGMENT -> {
                    // /epistola/platform/{role} → 4 segments
                    if (segments.size != 4) {
                        log.debug("Ignoring group '{}': expected /epistola/platform/{{role}}", group)
                        continue
                    }
                    val rolePart = segments[3]

                    val platformRole = KNOWN_PLATFORM_ROLES[rolePart]
                    if (platformRole != null) {
                        platformRoles.add(platformRole)
                    } else {
                        log.debug("Ignoring group '{}': unrecognized platform role '{}'", group, rolePart)
                    }
                }

                else -> log.debug("Ignoring group '{}': unrecognized category '{}'", group, segments[2])
            }
        }

        return ParsedGroupMemberships(
            tenantRoles = tenantRoles.mapValues { it.value.toSet() },
            globalRoles = globalRoles.toSet(),
            platformRoles = platformRoles.toSet(),
        )
    }
}
