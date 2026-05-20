package app.epistola.suite.keycloak

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class KeycloakAdminClientMapperLogicTest {

    private val canonicalGroups = canonicalGroupsMapper(DEFAULT_GROUPS_MAPPER_NAME)
    private val canonicalRealmRoles = canonicalRealmRolesMapper(DEFAULT_REALM_ROLES_MAPPER_NAME, "roles")

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

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME, canonicalGroups)

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

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME, canonicalGroups)

        assertThat(action).isEqualTo(MapperAction.Update("mapper-uuid"))
    }

    @Test
    fun `skips when our mapper already matches canonical config`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "mapper-uuid",
                "name" to DEFAULT_GROUPS_MAPPER_NAME,
                "protocolMapper" to "oidc-group-membership-mapper",
                "config" to canonicalGroups["config"] as Map<*, *>,
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME, canonicalGroups)

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

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME, canonicalGroups)

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

        val action = decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME, canonicalGroups)

        assertThat(action).isEqualTo(MapperAction.Create)
    }

    @Test
    fun `creates realm-roles mapper when none exists`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "groups-uuid",
                "name" to DEFAULT_GROUPS_MAPPER_NAME,
                "protocolMapper" to "oidc-group-membership-mapper",
                "config" to canonicalGroups["config"] as Map<*, *>,
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_REALM_ROLES_MAPPER_NAME, canonicalRealmRoles)

        assertThat(action).isEqualTo(MapperAction.Create)
    }

    @Test
    fun `updates realm-roles mapper when its config drifts`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "rr-uuid",
                "name" to DEFAULT_REALM_ROLES_MAPPER_NAME,
                "protocolMapper" to "oidc-usermodel-realm-role-mapper",
                "config" to mapOf(
                    "multivalued" to "false",
                    "claim.name" to "roles",
                    "id.token.claim" to "true",
                    "access.token.claim" to "true",
                    "userinfo.token.claim" to "true",
                    "jsonType.label" to "String",
                ),
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_REALM_ROLES_MAPPER_NAME, canonicalRealmRoles)

        assertThat(action).isEqualTo(MapperAction.Update("rr-uuid"))
    }

    @Test
    fun `skips realm-roles mapper when canonical`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "rr-uuid",
                "name" to DEFAULT_REALM_ROLES_MAPPER_NAME,
                "protocolMapper" to "oidc-usermodel-realm-role-mapper",
                "config" to canonicalRealmRoles["config"] as Map<*, *>,
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_REALM_ROLES_MAPPER_NAME, canonicalRealmRoles)

        assertThat(action).isEqualTo(MapperAction.SkipUpToDate)
    }

    @Test
    fun `warns when foreign realm-role mapper writes the same claim`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "foreign-rr",
                "name" to "legacy-realm-roles",
                "protocolMapper" to "oidc-usermodel-realm-role-mapper",
                "config" to mapOf("multivalued" to "true", "claim.name" to "roles"),
            ),
        )

        val action = decideMapperAction(existing, DEFAULT_REALM_ROLES_MAPPER_NAME, canonicalRealmRoles)

        assertThat(action).isEqualTo(MapperAction.SkipForeign("legacy-realm-roles"))
    }

    @Test
    fun `groups mapper and realm-roles mapper coexist independently`() {
        val existing = listOf(
            mapOf<String, Any>(
                "id" to "groups-uuid",
                "name" to DEFAULT_GROUPS_MAPPER_NAME,
                "protocolMapper" to "oidc-group-membership-mapper",
                "config" to canonicalGroups["config"] as Map<*, *>,
            ),
            mapOf<String, Any>(
                "id" to "rr-uuid",
                "name" to DEFAULT_REALM_ROLES_MAPPER_NAME,
                "protocolMapper" to "oidc-usermodel-realm-role-mapper",
                "config" to canonicalRealmRoles["config"] as Map<*, *>,
            ),
        )

        assertThat(decideMapperAction(existing, DEFAULT_GROUPS_MAPPER_NAME, canonicalGroups))
            .isEqualTo(MapperAction.SkipUpToDate)
        assertThat(decideMapperAction(existing, DEFAULT_REALM_ROLES_MAPPER_NAME, canonicalRealmRoles))
            .isEqualTo(MapperAction.SkipUpToDate)
    }
}
