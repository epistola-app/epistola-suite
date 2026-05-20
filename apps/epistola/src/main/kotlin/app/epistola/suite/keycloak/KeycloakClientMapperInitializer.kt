package app.epistola.suite.keycloak

import app.epistola.suite.security.AuthProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException

/**
 * Ensures Epistola's protocol mappers exist on the configured Keycloak client so the JWT
 * carries the claims our security layer reads:
 *
 * - the Group Membership mapper emits hierarchical paths into the `groups` claim
 *   (consumed by [app.epistola.suite.security.GroupMembershipParser])
 * - the Realm Role mapper emits realm role names into the configured flat-roles claim
 *   (consumed by [app.epistola.suite.security.FlatRoleParser])
 *
 * Both mappers are auto-provisioned so Keycloak works in either mode out of the box.
 * Self-heals only mappers named [DEFAULT_GROUPS_MAPPER_NAME] and [DEFAULT_REALM_ROLES_MAPPER_NAME];
 * foreign mappers writing the same claim are logged and left untouched.
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
    private val authProperties: AuthProperties,
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
            keycloakAdminClient.ensureRealmRolesMapper(uuid, authProperties.flatRoles.claimName)
        } catch (e: HttpClientErrorException.Forbidden) {
            log.warn(
                "Cannot manage protocol mappers on client '{}': the service account is missing the " +
                    "'manage-clients' realm-management role. Either grant it, or configure the Group " +
                    "Membership and Realm Role mappers manually (see docs/keycloak-setup.md).",
                clientId,
            )
        } catch (e: Exception) {
            log.warn("Failed to ensure protocol mappers on client '{}': {}", clientId, e.message)
        }
    }
}
