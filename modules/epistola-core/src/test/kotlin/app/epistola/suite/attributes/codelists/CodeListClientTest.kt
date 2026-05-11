package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.service.CodeListClient
import app.epistola.suite.attributes.codelists.service.CodeListFetchException
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Coverage for the classpath/URL fetch path used by `RefreshCodeList`.
 *
 * URL fetches are intentionally not exercised here — they would require a stub
 * HTTP server. The Playwright UI test covers that end-to-end.
 */
class CodeListClientTest : IntegrationTestBase() {

    @Autowired
    private lateinit var client: CodeListClient

    @Test
    fun `fetches entries from a classpath JSON resource`() {
        val entries = client.fetchEntries(
            url = "classpath:code-lists/test-fixture.json",
            authType = AuthType.NONE,
            credential = null,
        )
        assertThat(entries).extracting<String> { it.code }.containsExactly("en", "nl", "de")
        assertThat(entries.first { it.code == "nl" }.label).isEqualTo("Dutch")
    }

    @Test
    fun `rejects classpath URL that does not exist`() {
        assertThatThrownBy {
            client.fetchEntries("classpath:code-lists/missing.json", AuthType.NONE, null)
        }.isInstanceOf(CodeListFetchException::class.java)
            .hasMessageContaining("Classpath resource not found")
    }

    @Test
    fun `rejects URL whose scheme is not in the allowlist`() {
        assertThatThrownBy {
            client.fetchEntries("ftp://example.com/list.json", AuthType.NONE, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported URL scheme")
    }

    @Test
    fun `rejects URL that does not point to a json file`() {
        assertThatThrownBy {
            client.fetchEntries("https://example.com/list.txt", AuthType.NONE, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(".json")
    }

    @Test
    fun `rejects file URL with path traversal`() {
        assertThatThrownBy {
            client.fetchEntries("file:///etc/../../secrets.json", AuthType.NONE, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Path traversal")
    }
}
