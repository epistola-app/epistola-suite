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
 * operations for tenant provisioning using hierarchical group paths.
 */
class KeycloakAdminClient(
    private val restClient: RestClient,
    private val properties: KeycloakAdminProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Finds a group by its full path (e.g., `/epistola/tenants/demo`).
     *
     * Uses the Keycloak 24+ `group-by-path` endpoint.
     *
     * @return the group representation map, or null if not found
     */
    @Suppress("UNCHECKED_CAST")
    fun findGroupByPath(path: String): Map<String, Any>? = try {
        restClient.get()
            .uri("/admin/realms/{realm}/group-by-path/{path}", properties.realm, path.removePrefix("/"))
            .headers { it.setBearerAuth(obtainAccessToken()) }
            .retrieve()
            .body(Map::class.java) as? Map<String, Any>
    } catch (e: org.springframework.web.client.HttpClientErrorException.NotFound) {
        null
    }

    /**
     * Creates a top-level group in Keycloak.
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

        return extractGroupIdFromLocation(response, name)
    }

    /**
     * Creates a sub-group under the given parent group.
     *
     * @return the sub-group ID assigned by Keycloak
     */
    fun createSubGroup(parentId: UUID, name: String): UUID {
        log.info("Creating Keycloak sub-group '{}' under parent {}", name, parentId)

        val response = restClient.post()
            .uri("/admin/realms/{realm}/groups/{parentId}/children", properties.realm, parentId)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { it.setBearerAuth(obtainAccessToken()) }
            .body(mapOf("name" to name))
            .retrieve()
            .toBodilessEntity()

        return extractGroupIdFromLocation(response, name)
    }

    /**
     * Ensures a full group path exists, creating any missing segments.
     *
     * For example, `ensureGroupPath("/epistola/tenants/demo/reader")` will create
     * `ep`, `tenants`, `demo`, and `reader` groups as needed.
     *
     * @return the UUID of the leaf group
     */
    fun ensureGroupPath(path: String): UUID {
        val segments = path.removePrefix("/").split('/')
        require(segments.isNotEmpty()) { "Group path must have at least one segment" }

        var currentPath = ""
        var parentId: UUID? = null

        for (segment in segments) {
            currentPath = "$currentPath/$segment"

            val existing = findGroupByPath(currentPath)
            if (existing != null) {
                parentId = UUID.fromString(existing["id"].toString())
            } else if (parentId == null) {
                parentId = createGroup(segment)
            } else {
                parentId = createSubGroup(parentId, segment)
            }
        }

        return parentId!!
    }

    /**
     * Deletes a group by ID. Keycloak cascades deletion to all sub-groups.
     */
    fun deleteGroup(groupId: UUID) {
        log.info("Deleting Keycloak group: {}", groupId)

        restClient.delete()
            .uri("/admin/realms/{realm}/groups/{groupId}", properties.realm, groupId)
            .headers { it.setBearerAuth(obtainAccessToken()) }
            .retrieve()
            .toBodilessEntity()
    }

    private fun extractGroupIdFromLocation(response: org.springframework.http.ResponseEntity<Void>, name: String): UUID {
        val location = response.headers.location
            ?: throw KeycloakAdminException("No Location header in group creation response for: $name")

        val groupId = location.path.substringAfterLast('/')
        return UUID.fromString(groupId)
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
