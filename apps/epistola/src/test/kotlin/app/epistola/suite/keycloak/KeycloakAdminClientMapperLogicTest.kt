package app.epistola.suite.keycloak

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class KeycloakAdminClientMapperLogicTest {

    @Test
    fun `creates mapper when no existing groups-claim mapper is present`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "abc",
                "name" to "audience",
                "protocolMapper" to "oidc-audience-mapper",
                "config" to mapOf("included.client.audience" to "epistola-suite"),
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME)

        assertThat(action).isEqualTo(MapperAction.Create)
    }

    @Test
    fun `updates our mapper when its config drifts from canonical`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "mapper-uuid",
                "name" to DEFAULT_GROUPS_MAPPER_NAME,
                "protocolMapper" to "oidc-group-membership-mapper",
                "config" to mapOf(
                    "full.path" to "false",
                    "claim.name" to "groups",
                    "id.token.claim" to "true",
                    "access.token.claim" to "true",
                    "userinfo.token.claim" to "true",
                ),
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME)

        assertThat(action).isEqualTo(MapperAction.Update("mapper-uuid"))
    }

    @Test
    fun `skips when our mapper already matches canonical config`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "mapper-uuid",
                "name" to DEFAULT_GROUPS_MAPPER_NAME,
                "protocolMapper" to "oidc-group-membership-mapper",
                "config" to canonicalGroupsMapper(DEFAULT_GROUPS_MAPPER_NAME)["config"] as Map<*, *>,
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME)

        assertThat(action).isEqualTo(MapperAction.SkipUpToDate)
    }

    @Test
    fun `warns and leaves alone when foreign mapper writes groups claim`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "legacy-uuid",
                "name" to "group-membership-mapper",
                "protocolMapper" to "oidc-group-membership-mapper",
                "config" to mapOf("full.path" to "true", "claim.name" to "groups"),
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME)

        assertThat(action).isEqualTo(MapperAction.SkipForeign("group-membership-mapper"))
    }

    @Test
    fun `ignores group-membership mappers writing a different claim name`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "other",
                "name" to "groups-as-roles",
                "protocolMapper" to "oidc-group-membership-mapper",
                "config" to mapOf("full.path" to "true", "claim.name" to "roles"),
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME)

        assertThat(action).isEqualTo(MapperAction.Create)
    }
}
