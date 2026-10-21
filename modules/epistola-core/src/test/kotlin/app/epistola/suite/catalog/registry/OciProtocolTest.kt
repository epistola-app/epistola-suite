// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI

class OciProtocolTest {

    private val mapper = JsonMapper.builder().build()

    private val labels = ReleaseLabels("1.2.3", "v5:abc", "exchange.epistola.app", "acme0", "letters")

    @Test
    fun `a release manifest names the archive layer, the artifact type and every label, and reads back`() {
        val config = OciDescriptor(OciMediaTypes.CONFIG, OciDigest.sha256("{}".toByteArray()), 2)
        val archive = OciDescriptor(OciMediaTypes.ARCHIVE, OciDigest.sha256("zip".toByteArray()), 3, mapOf(OciAnnotations.IMAGE_TITLE to "letters-1.2.3.zip"))

        val bytes = ReleaseArtifact.manifest(mapper, labels, config, archive)
        val parsed = OciManifest.parse(mapper, bytes)

        assertThat(parsed.mediaType).isEqualTo(OciMediaTypes.MANIFEST)
        assertThat(parsed.artifactType).isEqualTo(OciMediaTypes.ARTIFACT)
        assertThat(parsed.isCatalogRelease).isTrue()
        assertThat(parsed.config).isEqualTo(config)
        assertThat(parsed.archive).isEqualTo(archive)
        assertThat(parsed.version).isEqualTo("1.2.3")
        assertThat(parsed.fingerprint).isEqualTo("v5:abc")
        assertThat(parsed.annotations).containsEntry(OciAnnotations.IMAGE_VERSION, "1.2.3")
            .containsEntry(OciAnnotations.NAMESPACE, "acme0")
            .containsEntry(OciAnnotations.CATALOG_KEY, "letters")
            .containsEntry(OciAnnotations.REGISTRY, "exchange.epistola.app")
        assertThat(ReleaseArtifact.manifest(mapper, labels, config, archive)).`as`("same release, same bytes").isEqualTo(bytes)
    }

    @Test
    fun `a manifest that is not a catalog release is recognised as such`() {
        val image = """{"schemaVersion":2,"mediaType":"${OciMediaTypes.MANIFEST}","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"${OciDigest.sha256(ByteArray(0))}","size":0},"layers":[]}"""
        val parsed = OciManifest.parse(mapper, image.toByteArray())
        assertThat(parsed.isCatalogRelease).isFalse()
        assertThat(parsed.archive).isNull()
    }

    @Test
    fun `a manifest without schema version 2 or with an unsupported digest is refused`() {
        assertThatThrownBy { OciManifest.parse(mapper, """{"schemaVersion":1}""".toByteArray()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            OciManifest.parse(mapper, """{"schemaVersion":2,"layers":[{"mediaType":"x","digest":"md5:00","size":1}]}""".toByteArray())
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OciManifest.parse(mapper, "not json".toByteArray()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a Bearer challenge is read with a scope that holds commas`() {
        val challenge = AuthChallenge.parse("""Bearer realm="https://auth.example.com/token",service="registry.example.com",scope="repository:acme/letters:pull,push"""")!!
        assertThat(challenge.isBearer).isTrue()
        assertThat(challenge.params).containsExactlyInAnyOrderEntriesOf(
            mapOf("realm" to "https://auth.example.com/token", "service" to "registry.example.com", "scope" to "repository:acme/letters:pull,push"),
        )
    }

    @Test
    fun `a Basic challenge, unquoted parameters and junk are read for what they are`() {
        assertThat(AuthChallenge.parse("""Basic realm="Registry Realm"""")!!.let { it.isBasic to it.params["realm"] }).isEqualTo(true to "Registry Realm")
        assertThat(AuthChallenge.parse("bearer realm=https://a/token, service=svc")!!.let { it.isBearer to it.params }).isEqualTo(true to mapOf("realm" to "https://a/token", "service" to "svc"))
        assertThat(AuthChallenge.parse(null)).isNull()
        assertThat(AuthChallenge.parse("   ")).isNull()
    }

    @Test
    fun `the next page of a tag list is followed from the Link header`() {
        val base = URI("https://registry.example.com/v2/acme/letters/tags/list?n=2")
        assertThat(LinkHeader.next("""</v2/acme/letters/tags/list?last=1.0.1&n=2>; rel="next"""", base))
            .isEqualTo(URI("https://registry.example.com/v2/acme/letters/tags/list?last=1.0.1&n=2"))
        assertThat(LinkHeader.next("""<https://other/v2/x?last=a>; rel=next""", base)).isEqualTo(URI("https://other/v2/x?last=a"))
        assertThat(LinkHeader.next("""</v2/x?last=a>; rel="prev"""", base)).isNull()
        assertThat(LinkHeader.next(null, base)).isNull()
    }

    @Test
    fun `a tag list without tags is empty`() {
        assertThat(parseTagList(mapper, """{"name":"acme/letters","tags":null}""".toByteArray())).isEmpty()
        assertThat(parseTagList(mapper, """{"name":"acme/letters","tags":["1.0.0","latest"]}""".toByteArray())).containsExactly("1.0.0", "latest")
    }

    @Test
    fun `release numbers are ordered by number, highest first, and other tags are left out`() {
        assertThat(releaseVersionsNewestFirst(listOf("1.10.0", "1.2.0", "latest", "2.0.0", "1.9.9", "v3.0.0", "01.0.0", "1.2.0")))
            .containsExactly("2.0.0", "1.10.0", "1.9.9", "1.2.0")
    }

    @Test
    fun `a repository is prefix, namespace and key, each a repository path component`() {
        assertThat(RegistryRepository.of("", "acme0", "letters")).isEqualTo("acme0/letters")
        assertThat(RegistryRepository.of("team/catalogs", "acme0", "letters")).isEqualTo("team/catalogs/acme0/letters")
        assertThatThrownBy { RegistryRepository.of("", "Acme", "letters") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RegistryRepository.of("team//x", "acme0", "letters") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RegistryRepository.of("", "acme0", "../x") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a registry is configured by name and https address, without credentials in the address`() {
        val ok = CatalogRegistryProperties.Registry(name = "acme", url = "https://registry.acme.example:5000/", repositoryPrefix = "/epistola/catalogs/", username = "u", password = "secret")
        assertThat(ok.location).isEqualTo("registry.acme.example:5000/epistola/catalogs")
        assertThat(ok.toString()).doesNotContain("secret")

        assertThatThrownBy { CatalogRegistryProperties.Registry(name = "Acme", url = "https://r") }.hasMessageContaining("lowercase slug")
        assertThatThrownBy { CatalogRegistryProperties.Registry(name = "a", url = "ftp://r") }.hasMessageContaining("needs a url")
        assertThatThrownBy { CatalogRegistryProperties.Registry(name = "a", url = "https://u:p@r") }.hasMessageContaining("without a path, query or credentials")
        assertThatThrownBy { CatalogRegistryProperties.Registry(name = "a", url = "https://r/v2") }.hasMessageContaining("without a path")
        assertThatThrownBy { CatalogRegistryProperties.Registry(name = "a", url = "https://r", password = "p") }.hasMessageContaining("no username")
        assertThatThrownBy {
            CatalogRegistryProperties(listOf(CatalogRegistryProperties.Registry("a", "https://r"), CatalogRegistryProperties.Registry("a", "https://s")))
        }.hasMessageContaining("more than once")
    }

    @Test
    fun `a registry source address names the registry, the namespace and the key`() {
        val uri = RegistrySourceUri("local", "acme0", "letters")
        assertThat(uri.toString()).isEqualTo("oci:local/acme0/letters")
        assertThat(RegistrySourceUri.parse(uri.toString())).isEqualTo(uri)
        assertThat(RegistrySourceUri.parse("exchange:acme0/letters")).isNull()
        assertThat(RegistrySourceUri.parse("oci:local/letters")).isNull()
    }
}
