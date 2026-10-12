// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The outbound source rules, without DNS: host names resolve through a stub table, so what is
 * tested is the decision, not the network.
 */
class OutboundUrlGuardTest {

    private val dns = mapOf(
        "catalog.example" to listOf("93.184.216.34"),
        "v6.example" to listOf("2606:2800:220:1:248:1893:25c8:1946"),
        "internal.example" to listOf("10.1.2.3"),
        "metadata.example" to listOf("169.254.169.254"),
        "mixed.example" to listOf("93.184.216.34", "127.0.0.1"),
        "mapped.example" to listOf("::ffff:127.0.0.1"),
    )

    private val stubResolver = HostResolver { host ->
        dns[host]?.map { InetAddress.ofLiteral(it) }?.toTypedArray() ?: throw UnknownHostException(host)
    }

    private fun guard(properties: CatalogSourceProperties = CatalogSourceProperties()) = OutboundUrlGuard(properties, stubResolver)

    private fun rejected(block: () -> Unit) = assertThatThrownBy(block).isInstanceOf(OutboundUrlRejectedException::class.java)

    @Nested
    inner class Addresses {

        @ParameterizedTest
        @ValueSource(
            strings = [
                // IPv4 private, loopback, link-local and special-purpose ranges.
                "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.254",
                "127.0.0.1", "127.255.255.254", "169.254.169.254", "169.254.0.1", "172.16.0.1",
                "172.31.255.255", "192.168.0.1", "198.18.0.1", "198.19.255.255", "224.0.0.1",
                "239.255.255.250", "240.0.0.1", "255.255.255.255", "192.0.2.1", "203.0.113.5",
                // IPv6 forms, including every way an IPv4 address can ride inside one.
                "[::]", "[::1]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "[febf::1]", "[ff02::1]",
                "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "[::ffff:169.254.169.254]", "[::ffff:10.0.0.1]",
                "[::127.0.0.1]", "[::10.0.0.1]", "[64:ff9b::7f00:1]", "[64:ff9b::a9fe:a9fe]",
                "[2002:7f00:0001::1]", "[2002:a9fe:a9fe::]", "[2001:0:4136:e378:8000:63bf:3fff:fdd2]",
                "[2001:db8::1]", "[fd00:ec2::254]", "[64:ff9b:1::1]",
                // Names that never mean anything but this host.
                "localhost", "LOCALHOST", "api.localhost", "localhost.",
            ],
        )
        fun `a non-public address is refused`(host: String) {
            rejected { guard().checkSource("https://$host/catalog.json", allowHttp = false) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["2130706433", "017700000001", "0x7f000001", "0x7f.0.0.1", "0177.0.0.1", "127.1", "127.0.1", "127.000.000.001", "1.1.1.1.1"])
        fun `a numeric host in a non-standard form is refused`(host: String) {
            rejected { guard().checkSource("https://$host/catalog.json", allowHttp = false) }
        }

        @Test
        fun `a host resolving to a private address is refused`() {
            rejected { guard().checkSource("https://internal.example/catalog.json", allowHttp = false) }
            rejected { guard().checkSource("https://metadata.example/catalog.json", allowHttp = false) }
            rejected { guard().checkSource("https://mapped.example/catalog.json", allowHttp = false) }
        }

        @Test
        fun `one private address among public ones is enough to refuse`() {
            rejected { guard().checkSource("https://mixed.example/catalog.json", allowHttp = false) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["catalog.example", "v6.example", "93.184.216.34", "[2606:2800:220:1:248:1893:25c8:1946]", "8.8.8.8", "[64:ff9b::808:808]", "[2002:808:808::1]"])
        fun `a public address is allowed`(host: String) {
            assertDoesNotThrow { guard().checkSource("https://$host/catalog.json", allowHttp = false) }
        }

        @Test
        fun `an unresolvable host is left to the fetch`() {
            assertDoesNotThrow { guard().checkSource("https://nowhere.example/catalog.json", allowHttp = false) }
            assertThatThrownBy { guard().resolveForConnection("nowhere.example") }.isInstanceOf(UnknownHostException::class.java)
        }

        @Test
        fun `allow-private-networks opens private ranges but never link-local or unspecified`() {
            val open = guard(CatalogSourceProperties(outbound = CatalogSourceProperties.Outbound(allowPrivateNetworks = true)))

            assertDoesNotThrow { open.checkSource("https://internal.example/catalog.json", allowHttp = false) }
            assertDoesNotThrow { open.checkSource("https://127.0.0.1/catalog.json", allowHttp = false) }
            assertDoesNotThrow { open.checkSource("https://localhost/catalog.json", allowHttp = false) }
            rejected { open.checkSource("https://169.254.169.254/catalog.json", allowHttp = false) }
            rejected { open.checkSource("https://metadata.example/catalog.json", allowHttp = false) }
            rejected { open.checkSource("https://0.0.0.0/catalog.json", allowHttp = false) }
            rejected { open.checkSource("https://[fe80::1]/catalog.json", allowHttp = false) }
            rejected { open.checkSource("https://[fd00:ec2::254]/catalog.json", allowHttp = false) }
        }

        @Test
        fun `allowed-hosts opens one host only`() {
            val open = guard(CatalogSourceProperties(outbound = CatalogSourceProperties.Outbound(allowedHosts = listOf("Internal.Example"))))

            assertDoesNotThrow { open.checkSource("https://internal.example/catalog.json", allowHttp = false) }
            rejected { open.checkSource("https://10.1.2.3/catalog.json", allowHttp = false) }
        }

        @Test
        fun `the connection resolves through the same rules`() {
            assertThat(guard().resolveForConnection("catalog.example").map { it.hostAddress }).containsExactly("93.184.216.34")
            rejected { guard().resolveForConnection("internal.example") }
            rejected { guard().resolveForConnection("mixed.example") }
            rejected { guard().resolveForConnection("127.0.0.1") }
        }
    }

    @Nested
    inner class Schemes {

        @Test
        fun `http only where allowed`() {
            rejected { guard().checkSource("http://catalog.example/catalog.json", allowHttp = false) }
            assertDoesNotThrow { guard().checkSource("http://catalog.example/catalog.json", allowHttp = true) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["ftp://catalog.example/c.json", "jar:file:///x.jar!/c.json", "gopher://catalog.example/c.json", "HTTPS://catalog.example/c.json", "exchange:acme/invoices", "c.json"])
        fun `other schemes are refused`(url: String) {
            rejected { guard().checkSource(url, allowHttp = true) }
        }

        @Test
        fun `a classpath source must be a catalog Epistola ships`() {
            assertDoesNotThrow { guard().checkSource("classpath:epistola/catalogs/system/catalog.json", allowHttp = false) }
            rejected { guard().checkSource("classpath:application.json", allowHttp = false) }
            rejected { guard().checkSource("classpath:epistola/catalogs/../../application.json", allowHttp = false) }
            rejected { guard().checkSource("classpath:epistola/catalogs//x.json", allowHttp = false) }
            rejected { guard().checkSource("classpath:epistola/catalogs/%2e%2e/x.json", allowHttp = false) }
        }

        @Test
        fun `derived content keeps its manifest's scheme`() {
            val g = guard()
            val https = "https://catalog.example/catalog.json"

            assertDoesNotThrow { g.checkDerived("https://catalog.example/img.png", https, allowHttp = true) }
            rejected { g.checkDerived("http://catalog.example/img.png", https, allowHttp = true) }
            rejected { g.checkDerived("file:///etc/passwd", https, allowHttp = true) }
            rejected { g.checkDerived("classpath:epistola/catalogs/system/catalog.json", https, allowHttp = true) }
            rejected { g.checkDerived("https://169.254.169.254/latest/meta-data", https, allowHttp = true) }

            val bundled = "classpath:epistola/catalogs/system/catalog.json"
            assertDoesNotThrow { g.checkDerived("classpath:epistola/catalogs/system/resources/a.json", bundled, allowHttp = false) }
            rejected { g.checkDerived("https://catalog.example/a.json", bundled, allowHttp = false) }
            rejected { g.checkDerived("file:///etc/passwd", bundled, allowHttp = false) }
        }

        @Test
        fun `a redirect may not leave http or downgrade from https`() {
            val g = guard()
            val from = URI("https://catalog.example/catalog.json")

            assertDoesNotThrow { g.checkRedirect(from, URI("https://v6.example/catalog.json"), allowHttp = true) }
            rejected { g.checkRedirect(from, URI("http://catalog.example/catalog.json"), allowHttp = true) }
            rejected { g.checkRedirect(from, URI("file:///etc/passwd"), allowHttp = true) }
            rejected { g.checkRedirect(from, URI("https://127.0.0.1/catalog.json"), allowHttp = true) }
            rejected { g.checkRedirect(from, URI("https://metadata.example/x"), allowHttp = true) }
        }
    }

    @Nested
    inner class FileRoots {

        private fun rootedAt(vararg roots: Path) = guard(CatalogSourceProperties(fileRoots = roots.map { it.toString() }))

        @Test
        fun `file sources are off without configured roots`(@TempDir dir: Path) {
            val file = Files.writeString(dir.resolve("catalog.json"), "{}")
            rejected { guard().checkSource(file.toUri().toString(), allowHttp = false) }
        }

        @Test
        fun `a file inside a root is allowed and returned canonical`(@TempDir dir: Path) {
            val file = Files.writeString(Files.createDirectories(dir.resolve("a/b")).resolve("catalog.json"), "{}")
            val g = rootedAt(dir)

            assertDoesNotThrow { g.checkSource(file.toUri().toString(), allowHttp = false) }
            assertThat(g.requireFileUnderRoots(file.toUri().toString())).isEqualTo(file.toRealPath())
        }

        @Test
        fun `a file outside every root is refused`(@TempDir root: Path, @TempDir elsewhere: Path) {
            val file = Files.writeString(elsewhere.resolve("catalog.json"), "{}")
            rejected { rootedAt(root).checkSource(file.toUri().toString(), allowHttp = false) }
            rejected { rootedAt(root).checkSource("file:///etc/passwd", allowHttp = false) }
        }

        @Test
        fun `a missing file is refused with the same message as an outside one`(@TempDir root: Path) {
            val missing = assertThatThrownBy { rootedAt(root).requireFileUnderRoots(root.resolve("nope.json").toUri().toString()) }
            val outside = assertThatThrownBy { rootedAt(root).requireFileUnderRoots("file:///etc/passwd") }
            missing.isInstanceOf(OutboundUrlRejectedException::class.java)
            outside.isInstanceOf(OutboundUrlRejectedException::class.java)
            assertThat(messageOf { rootedAt(root).requireFileUnderRoots(root.resolve("nope.json").toUri().toString()) })
                .isEqualTo(messageOf { rootedAt(root).requireFileUnderRoots("file:///etc/passwd") })
        }

        @Test
        fun `a symbolic link out of the root is refused`(@TempDir root: Path, @TempDir elsewhere: Path) {
            val target = Files.writeString(elsewhere.resolve("secret.json"), "{}")
            val link = Files.createSymbolicLink(root.resolve("catalog.json"), target)
            rejected { rootedAt(root).checkSource(link.toUri().toString(), allowHttp = false) }

            val dirLink = Files.createSymbolicLink(root.resolve("escape"), elsewhere)
            rejected { rootedAt(root).checkSource(dirLink.resolve("secret.json").toUri().toString(), allowHttp = false) }
        }

        @Test
        fun `a root that is itself a symbolic link is followed`(@TempDir dir: Path) {
            val real = Files.createDirectories(dir.resolve("real"))
            val file = Files.writeString(real.resolve("catalog.json"), "{}")
            val linkedRoot = Files.createSymbolicLink(dir.resolve("linked"), real)

            assertDoesNotThrow { rootedAt(linkedRoot).checkSource(file.toUri().toString(), allowHttp = false) }
        }

        @Test
        fun `dot-dot segments are refused even when they stay inside`(@TempDir root: Path) {
            Files.writeString(Files.createDirectories(root.resolve("a")).resolve("catalog.json"), "{}")
            rejected { rootedAt(root).checkSource("file://${root.toRealPath()}/a/../a/catalog.json", allowHttp = false) }
            rejected { rootedAt(root).checkSource("file://${root.toRealPath()}/a/%2e%2e/a/catalog.json", allowHttp = false) }
        }

        @Test
        fun `a directory is not a file`(@TempDir root: Path) {
            Files.createDirectories(root.resolve("dir.json"))
            rejected { rootedAt(root).checkSource(root.resolve("dir.json").toUri().toString(), allowHttp = false) }
        }

        @Test
        fun `a file URL with a host is refused`(@TempDir root: Path) {
            rejected { rootedAt(root).checkSource("file://server/share/catalog.json", allowHttp = false) }
        }

        @Test
        fun `derived file content must stay inside the roots`(@TempDir root: Path, @TempDir elsewhere: Path) {
            val manifest = Files.writeString(root.resolve("catalog.json"), "{}")
            val inside = Files.writeString(root.resolve("img.png"), "x")
            val outside = Files.writeString(elsewhere.resolve("img.png"), "x")
            val g = rootedAt(root)

            assertDoesNotThrow { g.checkDerived(inside.toUri().toString(), manifest.toUri().toString(), allowHttp = false) }
            rejected { g.checkDerived(outside.toUri().toString(), manifest.toUri().toString(), allowHttp = false) }
            rejected { g.checkDerived("https://catalog.example/img.png", manifest.toUri().toString(), allowHttp = false) }
        }

        private fun messageOf(block: () -> Unit): String? = runCatching(block).exceptionOrNull()?.message
    }
}
