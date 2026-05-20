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

    /**
     * Finds an OIDC client by its `clientId` attribute.
     *
     * @return the client representation map, or null if no client with that clientId exists
     */
    @Suppress("UNCHECKED_CAST")
    fun findClientByClientId(clientId: String): Map<String, Any>? {
        val results = restClient.get()
            .uri("/admin/realms/{realm}/clients?clientId={clientId}", properties.realm, clientId)
            .headers { it.setBearerAuth(obtainAccessToken()) }
            .retrieve()
            .body(List::class.java) as? List<Map<String, Any>>
            ?: return null

        return results.firstOrNull()
    }

    /**
     * Lists all protocol mappers configured directly on a client.
     */
    @Suppress("UNCHECKED_CAST")
    fun listClientProtocolMappers(clientUuid: String): List<Map<String, Any>> {
        val mappers = restClient.get()
            .uri(
                "/admin/realms/{realm}/clients/{clientUuid}/protocol-mappers/models",
                properties.realm,
                clientUuid,
            )
            .headers { it.setBearerAuth(obtainAccessToken()) }
            .retrieve()
            .body(List::class.java) as? List<Map<String, Any>>
            ?: return emptyList()

        return mappers
    }

    /**
     * Ensures a Group Membership protocol mapper named [mapperName] exists on the given client,
     * emitting full hierarchical group paths into the `groups` claim of all token types.
     *
     * Behaviour:
     * - No mapper writing `claim.name=groups` exists → creates one with the canonical config.
     * - A mapper named [mapperName] exists but its config differs from canonical → updates it (self-heal).
     * - A mapper with a *different* name already writes `claim.name=groups` → leaves it alone and
     *   logs a WARN, so operator-intentional config is not clobbered.
     */
    fun ensureGroupMembershipMapper(clientUuid: String, mapperName: String = DEFAULT_GROUPS_MAPPER_NAME) {
        val existing = listClientProtocolMappers(clientUuid)
        when (val action = decideMapperAction(existing, mapperName)) {
            is MapperAction.Create -> {
                log.info("Creating Group Membership mapper '{}' on client {}", mapperName, clientUuid)
                createProtocolMapper(clientUuid, canonicalGroupsMapper(mapperName))
            }
            is MapperAction.Update -> {
                log.info(
                    "Updating Group Membership mapper '{}' on client {} (self-heal: config drifted from canonical)",
                    mapperName,
                    clientUuid,
                )
                updateProtocolMapper(clientUuid, action.mapperId, canonicalGroupsMapper(mapperName) + ("id" to action.mapperId))
            }
            is MapperAction.SkipForeign -> {
                log.warn(
                    "Found existing protocol mapper '{}' on client {} writing claim.name=groups. " +
                        "Expected mapper name '{}'. Leaving foreign mapper alone — review manually if " +
                        "the groups claim is not behaving as expected.",
                    action.foreignName,
                    clientUuid,
                    mapperName,
                )
            }
            is MapperAction.SkipUpToDate -> {
                log.info("Group Membership mapper '{}' on client {} already matches canonical config", mapperName, clientUuid)
            }
        }
    }

    private fun createProtocolMapper(clientUuid: String, body: Map<String, Any>) {
        restClient.post()
            .uri(
                "/admin/realms/{realm}/clients/{clientUuid}/protocol-mappers/models",
                properties.realm,
                clientUuid,
            )
            .contentType(MediaType.APPLICATION_JSON)
            .headers { it.setBearerAuth(obtainAccessToken()) }
            .body(body)
            .retrieve()
            .toBodilessEntity()
    }

    private fun updateProtocolMapper(clientUuid: String, mapperId: String, body: Map<String, Any>) {
        restClient.put()
            .uri(
                "/admin/realms/{realm}/clients/{clientUuid}/protocol-mappers/models/{mapperId}",
                properties.realm,
                clientUuid,
                mapperId,
            )
            .contentType(MediaType.APPLICATION_JSON)
            .headers { it.setBearerAuth(obtainAccessToken()) }
            .body(body)
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

internal const val DEFAULT_GROUPS_MAPPER_NAME: String = "epistola-groups"

internal sealed interface MapperAction {
    data object Create : MapperAction
    data class Update(val mapperId: String) : MapperAction
    data class SkipForeign(val foreignName: String) : MapperAction
    data object SkipUpToDate : MapperAction
}

internal fun canonicalGroupsMapper(mapperName: String): Map<String, Any> = mapOf(
    "name" to mapperName,
    "protocol" to "openid-connect",
    "protocolMapper" to "oidc-group-membership-mapper",
    "consentRequired" to false,
    "config" to canonicalGroupsMapperConfig(),
)

private fun canonicalGroupsMapperConfig(): Map<String, String> = mapOf(
    "full.path" to "true",
    "id.token.claim" to "true",
    "access.token.claim" to "true",
    "claim.name" to "groups",
    "userinfo.token.claim" to "true",
)

internal fun decideMapperAction(existing: List<Map<String, Any>>, expectedName: String): MapperAction {
    val groupsMappers = existing.filter { mapper ->
        @Suppress("UNCHECKED_CAST")
        val config = mapper["config"] as? Map<String, Any> ?: emptyMap()
        mapper["protocolMapper"] == "oidc-group-membership-mapper" &&
            config["claim.name"]?.toString() == "groups"
    }

    val ours = groupsMappers.firstOrNull { it["name"]?.toString() == expectedName }
    if (ours != null) {
        @Suppress("UNCHECKED_CAST")
        val config = (ours["config"] as? Map<String, Any>).orEmpty()
        val canonical = canonicalGroupsMapperConfig()
        val matches = canonical.all { (k, v) -> config[k]?.toString() == v }
        return if (matches) {
            MapperAction.SkipUpToDate
        } else {
            MapperAction.Update(ours["id"].toString())
        }
    }

    val foreign = groupsMappers.firstOrNull()
    if (foreign != null) {
        return MapperAction.SkipForeign(foreign["name"]?.toString() ?: "<unnamed>")
    }

    return MapperAction.Create
}

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
