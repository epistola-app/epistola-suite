// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.encryption

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.crypto.Secret
import app.epistola.suite.exchange.ExchangeAuthorizationTransaction
import app.epistola.suite.exchange.ExchangeTenantConnection
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * End-to-end proof that the JDBI [app.epistola.suite.crypto.Secret] mappers
 * encrypt credentials at rest and decrypt them transparently on read. Uses a
 * URL-sourced code list because [CreateCodeList] persists its `credential`
 * without any outbound HTTP (the fetch only happens on refresh).
 */
class CredentialEncryptionIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val plaintext = "super-secret-bearer-token-12345"

    @Test
    fun `code list credential is ciphertext at rest and decrypts transparently`() {
        val tenant = createTenant("Crypto Tenant")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("secure-list"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "Secure List",
                sourceType = CodeListSource.URL,
                sourceUrl = "https://example.com/list.json",
                authType = AuthType.BEARER,
                credential = plaintext,
            ).execute()

            // Transparent decrypt on the way out.
            val loaded = GetCodeList(id = id).query()
            assertThat(loaded).isNotNull
            assertThat(loaded!!.credential?.value).isEqualTo(plaintext)
        }

        // Raw column read bypasses the Secret mapper: must be an enc: envelope,
        // and must NOT contain the plaintext anywhere.
        val raw = jdbi.withHandle<String, Exception> { handle ->
            handle.createQuery("SELECT credential FROM code_lists WHERE slug = :slug AND tenant_key = :tenant")
                .bind("slug", id.key.value)
                .bind("tenant", id.tenantKey.value)
                .mapTo(String::class.java)
                .one()
        }
        assertThat(raw).startsWith("enc:v1:")
        assertThat(raw).doesNotContain(plaintext)
    }

    @Test
    fun `Exchange application secret and PKCE verifier are encrypted at rest`() {
        val tenant = createTenant("Exchange Crypto Tenant")
        val clientSecret = Secret("exchange-client-secret")
        val verifier = Secret("exchange-pkce-verifier")

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO exchange_tenant_connections
                    (tenant_key, issuer, base_url, oauth_application_id, client_secret)
                VALUES (:tenant, 'https://exchange.example', 'https://exchange.example', :application, :secret)
                """,
            ).bind("tenant", tenant.id).bind("application", java.util.UUID.randomUUID())
                .bind("secret", clientSecret).execute()
            handle.createUpdate(
                """
                INSERT INTO exchange_oauth_authorizations
                    (tenant_key, state_hash, code_verifier, redirect_uri, expires_at)
                VALUES (:tenant, :stateHash, :verifier, 'https://suite.example/oauth/exchange/callback', NOW() + INTERVAL '5 minutes')
                """,
            ).bind("tenant", tenant.id).bind("stateHash", "a".repeat(64)).bind("verifier", verifier).execute()
        }

        val decrypted = jdbi.withHandle<Pair<Secret, Secret>, Exception> { handle ->
            val connection = handle.createQuery("SELECT * FROM exchange_tenant_connections WHERE tenant_key = :tenant")
                .bind("tenant", tenant.id).mapTo(ExchangeTenantConnection::class.java).one()
            val authorization = handle.createQuery("SELECT * FROM exchange_oauth_authorizations WHERE tenant_key = :tenant")
                .bind("tenant", tenant.id).mapTo(ExchangeAuthorizationTransaction::class.java).one()
            requireNotNull(connection.clientSecret) to authorization.codeVerifier
        }
        assertThat(decrypted.first.value).isEqualTo(clientSecret.value)
        assertThat(decrypted.second.value).isEqualTo(verifier.value)

        val raw = jdbi.withHandle<List<String>, Exception> { handle ->
            listOf(
                handle.createQuery("SELECT client_secret FROM exchange_tenant_connections WHERE tenant_key = :tenant")
                    .bind("tenant", tenant.id).mapTo(String::class.java).one(),
                handle.createQuery("SELECT code_verifier FROM exchange_oauth_authorizations WHERE tenant_key = :tenant")
                    .bind("tenant", tenant.id).mapTo(String::class.java).one(),
            )
        }
        assertThat(raw).allSatisfy { value ->
            assertThat(value).startsWith("enc:v1:")
            assertThat(value).doesNotContain("exchange-")
        }
    }
}
