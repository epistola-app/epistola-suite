// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

/**
 * The contract publishes the authorization vocabulary as data: `x-authorization` in the spec the
 * server stubs ship (`openapi/epistola-contract.yaml`). A 403 names a permission or role as the
 * contract does, and an identity provider emits the names it documents, so the suite's [Permission],
 * [TenantRole] and [PlatformRole], what each role grants, and the identity-provider names the parsers
 * accept must be the contract's, member for member (ADR 0048 section 6).
 */
@Tag("unit")
class PermissionContractAlignmentTest {

    private val spec: Map<String, Any?> = run {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("openapi/epistola-contract.yaml")) {
            "openapi/epistola-contract.yaml is not on the classpath: the server stubs ship it"
        }
        val options = LoaderOptions().apply { codePointLimit = 16 * 1024 * 1024 }
        stream.use { Yaml(options).load(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private val vocabulary = spec["x-authorization"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun entries(key: String): List<Map<String, Any?>> = vocabulary[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.names(key: String): Set<String> = (this[key] as List<String>?).orEmpty().toSet()

    @Test
    fun `every permission is the contract's, granted by the roles the contract names`() {
        val contract = entries("permissions").associate { it["name"] as String to it.names("grantedBy") }
        val suite = Permission.entries.associate { permission ->
            permission.name to TenantRole.entries.filter { permission in it.permissions() }.map { it.name }.toSet()
        }

        assertThat(suite).isEqualTo(contract)
    }

    @Test
    fun `every tenant role is the contract's, under the identity-provider name it documents`() {
        val contract = entries("tenantRoles").associate { it["idpName"] as String to it["name"] as String }

        assertThat(TenantRole.entries.map { it.name }).containsExactlyInAnyOrderElementsOf(contract.values)
        assertThat(KNOWN_TENANT_ROLES.mapValues { it.value.name }).isEqualTo(contract)
    }

    @Test
    fun `every platform role is the contract's, with what it grants in every tenant`() {
        val roles = entries("platformRoles")
        val contractNames = roles.associate { it["idpName"] as String to it["name"] as String }
        val contractGrants = roles.associate { it["name"] as String to it.names("grantsInEveryTenant") }

        assertThat(PlatformRole.entries.map { it.name }).containsExactlyInAnyOrderElementsOf(contractNames.values)
        assertThat(KNOWN_PLATFORM_ROLES.mapValues { it.value.name }).isEqualTo(contractNames)
        assertThat(PlatformRole.entries.associate { role -> role.name to role.permissionsInEveryTenant().map { it.name }.toSet() })
            .isEqualTo(contractGrants)
    }

    @Test
    fun `every operation requires a permission or platform role the suite knows`() {
        @Suppress("UNCHECKED_CAST")
        val operations = (spec["paths"] as Map<String, Map<String, Any?>>).values
            .flatMap { path -> path.values.filterIsInstance<Map<String, Any?>>() }
        val permissions = operations.flatMap { it.names("x-required-permissions") }.toSet()
        val platformRoles = operations.flatMap { it.names("x-required-platform-roles") }.toSet()

        assertThat(permissions).isNotEmpty()
        assertThat(Permission.entries.map { it.name }).containsAll(permissions)
        assertThat(PlatformRole.entries.map { it.name }).containsAll(platformRoles)
    }
}
