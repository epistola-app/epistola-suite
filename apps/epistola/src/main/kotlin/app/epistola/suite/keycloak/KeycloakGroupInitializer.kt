package app.epistola.suite.keycloak

import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.TenantRole
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Ensures the base Keycloak group hierarchy exists on application startup.
 *
 * Creates the following structure if any groups are missing:
 * ```
 * /epistola
 *   /epistola/tenants
 *   /epistola/global
 *     /epistola/global/reader
 *     /epistola/global/editor
 *     /epistola/global/generator
 *     /epistola/global/manager
 *   /epistola/platform
 *     /epistola/platform/tenant-manager
 * ```
 *
 * Enabled via `epistola.keycloak.ensure-groups=true`. Disabled by default.
 */
@Component
@ConditionalOnBean(KeycloakAdminClient::class)
@ConditionalOnProperty(name = ["epistola.keycloak.ensure-groups"], havingValue = "true")
class KeycloakGroupInitializer(
    private val keycloakAdminClient: KeycloakAdminClient,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        log.info("Ensuring base Keycloak group structure exists")

        val paths = buildList {
            add("/epistola/tenants")

            for (role in TenantRole.entries) {
                add("/epistola/global/${role.name.lowercase()}")
            }

            for (role in PlatformRole.entries) {
                add("/epistola/platform/${role.name.lowercase().replace('_', '-')}")
            }
        }

        for (path in paths) {
            try {
                keycloakAdminClient.ensureGroupPath(path)
                log.info("Ensured group path: {}", path)
            } catch (e: Exception) {
                log.warn("Failed to ensure group path '{}': {}", path, e.message)
            }
        }

        log.info("Base Keycloak group structure check complete")
    }
}
