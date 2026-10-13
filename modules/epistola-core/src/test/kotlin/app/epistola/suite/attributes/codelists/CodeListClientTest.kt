// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.service.CodeListClient
import app.epistola.suite.attributes.codelists.service.CodeListFetchException
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.outbound.OutboundUrlRejectedException
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coverage for the file fetch path and the source rules used by `RefreshCodeList`.
 *
 * HTTP fetches are covered by `RefreshCodeListTest`, which runs a loopback stub server. The test
 * profile names java.io.tmpdir as the file root, which is where @TempDir directories live.
 */
class CodeListClientTest : IntegrationTestBase() {

    @Autowired
    private lateinit var client: CodeListClient

    private fun fixtureIn(dir: Path): Path {
        val bytes = javaClass.classLoader.getResourceAsStream("code-lists/test-fixture.json")!!.use { it.readAllBytes() }
        return Files.write(dir.resolve("test-fixture.json"), bytes)
    }

    @Test
    fun `fetches entries from a file inside the configured root`(@TempDir dir: Path) {
        val entries = client.fetchEntries(
            url = fixtureIn(dir).toUri().toString(),
            authType = AuthType.NONE,
            credential = null,
        )
        assertThat(entries).extracting<String> { it.code }.containsExactly("en", "nl", "de")
        assertThat(entries.first { it.code == "nl" }.label).isEqualTo("Dutch")
    }

    @Test
    fun `rejects a classpath URL outside the bundled catalogs`() {
        assertThatThrownBy {
            client.fetchEntries("classpath:code-lists/test-fixture.json", AuthType.NONE, null)
        }.isInstanceOf(CodeListFetchException::class.java)
            .hasMessageContaining("Only catalogs bundled with Epistola may be read from the classpath")
    }

    @Test
    fun `rejects a file outside the configured roots`() {
        assertThatThrownBy {
            client.fetchEntries("file:///etc/hosts.json", AuthType.NONE, null)
        }.isInstanceOf(CodeListFetchException::class.java)
            .hasCauseInstanceOf(OutboundUrlRejectedException::class.java)
    }

    @Test
    fun `rejects URL whose scheme is not in the allowlist`() {
        assertThatThrownBy {
            client.fetchEntries("ftp://example.com/list.json", AuthType.NONE, null)
        }.isInstanceOf(CodeListFetchException::class.java)
            .hasMessageContaining("Unsupported URL scheme")
    }

    @Test
    fun `rejects URL that does not point to a json file`() {
        assertThatThrownBy {
            client.fetchEntries("https://example.com/list.txt", AuthType.NONE, null)
        }.isInstanceOf(CodeListFetchException::class.java)
            .hasMessageContaining(".json")
    }

    @Test
    fun `rejects file URL with path traversal`() {
        assertThatThrownBy {
            client.fetchEntries("file:///etc/../../secrets.json", AuthType.NONE, null)
        }.isInstanceOf(CodeListFetchException::class.java)
            .hasMessageContaining("Path traversal")
    }

    @Test
    fun `input validation names the field and the code`() {
        listOf(
            "classpath:code-lists/test-fixture.json",
            "file:///etc/passwd.json",
            "https://169.254.169.254/latest/meta-data.json",
            "https://[::ffff:169.254.169.254]/x.json",
            "https://0.0.0.0/x.json",
            "https://2130706433/x.json",
        ).forEach { url ->
            assertThatThrownBy { client.requireAllowedSourceUrl(url) }
                .describedAs(url)
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.field).isEqualTo("sourceUrl")
                    assertThat(it.code).isEqualTo(ValidationCode.SOURCE_URL_NOT_ALLOWED)
                }
        }
    }
}
