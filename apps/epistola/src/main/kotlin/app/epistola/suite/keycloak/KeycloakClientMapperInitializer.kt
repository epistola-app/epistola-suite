package app.epistola.suite.keycloak

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException

/**
 * Ensures the Group Membership protocol mapper exists on the configured Keycloak client
 * so the JWT `groups` claim contains full hierarchical group paths.
 *
 * The mapper is required for [app.epistola.suite.security.GroupMembershipParser] to map
 * Keycloak groups (e.g. `/epistola/platform/tenant-manager`) onto Epistola platform/tenant
 * roles. Without it, group memberships are silently dropped from the token.
 *
 * Self-heals only mappers named [DEFAULT_GROUPS_MAPPER_NAME]; a foreign mapper that also writes
 * a `groups` claim is logged and left untouched so operator-intentional config is preserved.
 *
 * Enabled via `epistola.keycloak.ensure-groups=true` (same flag as [KeycloakGroupInitializer]).
 * Requires the service account to have the realm-management role `manage-clients`.
 */
@Component
@ConditionalOnBean(KeycloakAdminClient::class)
@ConditionalOnProperty(name = ["epistola.keycloak.ensure-groups"], havingValue = "true")
class KeycloakClientMapperInitializer(
    private val keycloakAdminClient: KeycloakAdminClient,
    private val properties: KeycloakAdminProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val clientId = properties.clientId
        try {
            val client = keycloakAdminClient.findClientByClientId(clientId)
            if (client == null) {
                log.warn("Keycloak client '{}' not found in realm '{}' — skipping mapper provisioning", clientId, properties.realm)
                return
            }
            val uuid = client["id"]?.toString()
            if (uuid.isNullOrBlank()) {
                log.warn("Keycloak client '{}' lookup returned no 'id' field — skipping mapper provisioning", clientId)
                return
            }
            keycloakAdminClient.ensureGroupMembershipMapper(uuid)
        } catch (e: HttpClientErrorException.Forbidden) {
            log.warn(
                "Cannot manage protocol mappers on client '{}': the service account is missing the " +
                    "'manage-clients' realm-management role. Either grant it, or configure the Group " +
                    "Membership mapper manually (see docs/keycloak-setup.md).",
                clientId,
            )
        } catch (e: Exception) {
            log.warn("Failed to ensure Group Membership mapper on client '{}': {}", clientId, e.message)
        }
    }
}
