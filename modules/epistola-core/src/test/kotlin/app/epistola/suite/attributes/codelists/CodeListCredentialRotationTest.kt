// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.crypto.CredentialCipherFactory
import app.epistola.suite.crypto.EncryptionProperties
import app.epistola.suite.encryption.ReencryptCredentials
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.default
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/** Two keys of 32 bytes (`openssl rand -base64 32`), fixed for the test: the retired one and the primary. */
private const val OLD_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
private const val NEW_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="

/**
 * Rotating the encryption key re-encrypts a code list's credential where it is stored,
 * `resource_credentials` (ADR 0033): its content names it by id, so no content changes, and the
 * list reads the same secret after.
 */
@TestPropertySource(
    properties = [
        "epistola.encryption.primary-key-id=k-new",
        "epistola.encryption.keys[0].id=k-old",
        "epistola.encryption.keys[0].material=$OLD_KEY",
        "epistola.encryption.keys[1].id=k-new",
        "epistola.encryption.keys[1].material=$NEW_KEY",
    ],
)
class CodeListCredentialRotationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var cipher: CredentialCipher

    private fun stored(tenant: TenantId): Pair<String, String> = jdbi.withHandle<Pair<String, String>, Exception> { h ->
        h.createQuery(
            """
            SELECT rc.credential, r.content_hash FROM current_code_lists t
            JOIN resource_credentials rc ON rc.tenant_key = t.tenant_key AND rc.credential_id = t.credential_id
            JOIN release_resources r ON r.tenant_key = t.tenant_key AND r.version_id = t.version_id AND r.resource_type = 'codeList' AND r.resource_key = t.id
            WHERE t.tenant_key = :t AND t.id = 'remote'
            """,
        ).bind("t", tenant.key).map { rs, _ -> rs.getString(1) to rs.getString(2) }.one()
    }

    @Test
    fun `rotation re-encrypts the credential in place and changes no content`() {
        val tenant = TenantId(createTenant("Code list rotation").id)
        val id = CodeListId(CodeListKey.of("remote"), CatalogId.default(tenant))
        withMediator {
            CreateCodeList(id, "Remote", sourceType = CodeListSource.URL, sourceUrl = "https://example.com/list.json", authType = AuthType.BEARER, credential = "rotate-me").execute()
        }
        // A credential under the retired key, as one stored before the rotation is: no command
        // encrypts under a key that is not primary.
        val retired = CredentialCipherFactory.create(
            EncryptionProperties(enabled = true, primaryKeyId = "k-old", keys = listOf(EncryptionProperties.KeyMaterial("k-old", OLD_KEY))),
            isProdProfile = false,
        )
        jdbi.useHandle<Exception> { it.execute("UPDATE resource_credentials SET credential = ? WHERE tenant_key = ?", retired.encrypt("rotate-me"), tenant.key.value) }
        val (before, hashBefore) = stored(tenant)
        assertThat(cipher.keyIdOf(before)).isEqualTo("k-old")

        val result = withMediator { ReencryptCredentials().execute() }

        val (after, hashAfter) = stored(tenant)
        assertThat(result.credentialsReencrypted["resource_credentials.credential"]).isEqualTo(1)
        assertThat(cipher.keyIdOf(after)).isEqualTo("k-new")
        assertThat(hashAfter).`as`("the content names the credential by id, so it does not change").isEqualTo(hashBefore)
        assertThat(withMediator { GetCodeList(id).query()!! }.credential?.value).isEqualTo("rotate-me")
    }
}
