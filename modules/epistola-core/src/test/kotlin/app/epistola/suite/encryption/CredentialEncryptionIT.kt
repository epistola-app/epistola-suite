package app.epistola.suite.encryption

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
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
}
