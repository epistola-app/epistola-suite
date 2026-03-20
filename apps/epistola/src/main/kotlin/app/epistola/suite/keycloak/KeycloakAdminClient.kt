package app.epistola.suite.keycloak

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * Client for the Keycloak Admin REST API.
 *
 * Authenticates via client credentials (service account) and provides
 * operations for tenant provisioning: creating groups and organizations.
 */
class KeycloakAdminClient(
    private val restClient: RestClient,
    private val properties: KeycloakAdminProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Creates a group in Keycloak.
     *
     * @return the group ID assigned by Keycloak
     */
    fun createGroup(name: String): UUID {
        log.info("Creating Keycloak group: {}", name)

        val response = restClient.post()
            .uri("/admin/realms/{realm}/groups", properties.realm)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { it.setBearerAuth(obtainAccessToken()) }
            .body(mapOf("name" to name))
            .retrieve()
            .toBodilessEntity()

        // Keycloak returns the group ID in the Location header
        val location = response.headers.location
            ?: throw KeycloakAdminException("No Location header in group creation response for: $name")

        val groupId = location.path.substringAfterLast('/')
        return UUID.fromString(groupId)
    }

    /**
     * Deletes all groups matching the given prefix.
     * Used for cleanup when a tenant is deleted.
     */
    fun deleteGroupsByPrefix(prefix: String) {
        log.info("Deleting Keycloak groups with prefix: {}", prefix)

        val token = obtainAccessToken()

        @Suppress("UNCHECKED_CAST")
        val groups = restClient.get()
            .uri("/admin/realms/{realm}/groups?search={prefix}", properties.realm, prefix)
            .headers { it.setBearerAuth(token) }
            .retrieve()
            .body(List::class.java) as? List<Map<String, Any>> ?: emptyList()

        for (group in groups) {
            val name = group["name"]?.toString() ?: continue
            if (!name.startsWith(prefix)) continue

            val id = group["id"]?.toString() ?: continue
            log.info("Deleting Keycloak group: {} ({})", name, id)

            restClient.delete()
                .uri("/admin/realms/{realm}/groups/{groupId}", properties.realm, id)
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .toBodilessEntity()
        }
    }

    /**
     * Obtains an access token using client credentials grant.
     */
    private fun obtainAccessToken(): String {
        @Suppress("UNCHECKED_CAST")
        val tokenResponse = restClient.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials&client_id=${properties.clientId}&client_secret=${properties.clientSecret}")
            .retrieve()
            .body(Map::class.java) as? Map<String, Any>
            ?: throw KeycloakAdminException("Failed to obtain access token")

        return tokenResponse["access_token"]?.toString()
            ?: throw KeycloakAdminException("No access_token in token response")
    }
}

class KeycloakAdminException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties::class)
@ConditionalOnBean(ClientRegistrationRepository::class)
@ConditionalOnProperty(prefix = "epistola.keycloak", name = ["client-secret"])
class KeycloakAdminConfiguration {

    @Bean
    fun keycloakAdminClient(properties: KeycloakAdminProperties): KeycloakAdminClient {
        val restClient = RestClient.builder()
            .baseUrl(properties.adminUrl)
            .build()

        return KeycloakAdminClient(restClient, properties)
    }
}
