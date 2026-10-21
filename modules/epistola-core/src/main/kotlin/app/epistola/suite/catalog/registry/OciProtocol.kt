// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.SemVer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat

/*
 * The parts of the OCI distribution protocol a catalog release needs, as pure functions: what a
 * release looks like as an artifact, and how a registry's answers are read. Nothing here talks to
 * the network; [OciRegistryClient] does.
 */

/** The media types of a catalog release stored as an OCI artifact (ADR 0032, section 7). */
object OciMediaTypes {
    const val MANIFEST = "application/vnd.oci.image.manifest.v1+json"
    const val ARTIFACT = "application/vnd.epistola.catalog.release.v1"
    const val CONFIG = "application/vnd.epistola.catalog.release.config.v1+json"
    const val ARCHIVE = "application/vnd.epistola.catalog.archive.v1+zip"
}

/**
 * The annotations a release's manifest carries. **Labels, not evidence**: anyone who may push to
 * the repository can write any of them, so a receiver never trusts them for content. The import
 * recomputes the fingerprint from the archive; these only describe what a listing shows.
 */
object OciAnnotations {
    const val VERSION = "app.epistola.catalog.version"
    const val FINGERPRINT = "app.epistola.catalog.fingerprint"
    const val REGISTRY = "app.epistola.catalog.registry"
    const val NAMESPACE = "app.epistola.catalog.namespace"
    const val CATALOG_KEY = "app.epistola.catalog.catalogKey"
    const val IMAGE_VERSION = "org.opencontainers.image.version"
    const val IMAGE_TITLE = "org.opencontainers.image.title"
}

/** A content descriptor: what a manifest says about one blob. */
data class OciDescriptor(
    val mediaType: String,
    val digest: String,
    val size: Long,
    val annotations: Map<String, String> = emptyMap(),
)

/** What a release is, as its manifest and config name it. */
data class ReleaseLabels(
    val version: String,
    val fingerprint: String,
    val registry: String,
    val namespace: String,
    val catalogKey: String,
)

/** A manifest as read from a registry, whatever it holds; [isCatalogRelease] says whether it is one of ours. */
data class OciManifest(
    val mediaType: String?,
    val artifactType: String?,
    val config: OciDescriptor?,
    val layers: List<OciDescriptor>,
    val annotations: Map<String, String>,
) {
    /** A catalog release, by its artifact type or (for registries that drop `artifactType`) its config. */
    val isCatalogRelease: Boolean get() = artifactType == OciMediaTypes.ARTIFACT || config?.mediaType == OciMediaTypes.CONFIG

    /** The catalog archive layer, or null when the manifest has none. */
    val archive: OciDescriptor? get() = layers.firstOrNull { it.mediaType == OciMediaTypes.ARCHIVE }

    val version: String? get() = annotations[OciAnnotations.VERSION]
    val fingerprint: String? get() = annotations[OciAnnotations.FINGERPRINT]

    companion object {
        /** Reads a manifest's JSON. @throws IllegalArgumentException when it is not a manifest. */
        fun parse(mapper: ObjectMapper, bytes: ByteArray): OciManifest {
            val root = try {
                mapper.readTree(bytes)
            } catch (e: tools.jackson.core.JacksonException) {
                throw IllegalArgumentException("not JSON: ${e.originalMessage}", e)
            }
            require(root != null && root.isObject) { "not a JSON object" }
            require(root.path("schemaVersion").asInt(0) == 2) { "not a schemaVersion 2 manifest" }
            return OciManifest(
                mediaType = root.text("mediaType"),
                artifactType = root.text("artifactType"),
                config = root.path("config").takeIf { it.isObject }?.let(::descriptor),
                layers = root.path("layers").takeIf { it.isArray }?.let { array -> (0 until array.size()).map { descriptor(array.get(it)) } }.orEmpty(),
                annotations = root.stringMap("annotations"),
            )
        }

        private fun descriptor(node: JsonNode): OciDescriptor {
            val digest = requireNotNull(node.text("digest")) { "a descriptor without a digest" }
            require(OciDigest.isSha256(digest)) { "unsupported digest '$digest'" }
            return OciDescriptor(
                mediaType = node.text("mediaType") ?: "",
                digest = digest,
                size = node.path("size").asLong(-1).also { require(it >= 0) { "a descriptor without a size" } },
                annotations = node.stringMap("annotations"),
            )
        }

        private fun JsonNode.text(name: String): String? = path(name).takeIf { it.isString }?.stringValue()

        private fun JsonNode.stringMap(name: String): Map<String, String> {
            val node = path(name).takeIf { it.isObject } ?: return emptyMap()
            return node.propertyNames().asSequence()
                .mapNotNull { key -> node.path(key).takeIf { it.isString }?.stringValue()?.let { key to it } }
                .toMap()
        }
    }
}

/**
 * The artifact a release is pushed as: a config blob with its labels, one layer holding the
 * catalog archive, and a manifest naming both. Built with sorted keys so the same release always
 * produces the same bytes, and so the same manifest digest.
 */
object ReleaseArtifact {
    fun config(mapper: ObjectMapper, labels: ReleaseLabels): ByteArray = mapper.writeValueAsBytes(
        sortedMapOf(
            "catalogKey" to labels.catalogKey,
            "fingerprint" to labels.fingerprint,
            "namespace" to labels.namespace,
            "registry" to labels.registry,
            "version" to labels.version,
        ),
    )

    fun manifest(mapper: ObjectMapper, labels: ReleaseLabels, config: OciDescriptor, archive: OciDescriptor): ByteArray = mapper.writeValueAsBytes(
        linkedMapOf(
            "schemaVersion" to 2,
            "mediaType" to OciMediaTypes.MANIFEST,
            "artifactType" to OciMediaTypes.ARTIFACT,
            "config" to descriptor(config),
            "layers" to listOf(descriptor(archive)),
            "annotations" to sortedMapOf(
                OciAnnotations.CATALOG_KEY to labels.catalogKey,
                OciAnnotations.FINGERPRINT to labels.fingerprint,
                OciAnnotations.NAMESPACE to labels.namespace,
                OciAnnotations.REGISTRY to labels.registry,
                OciAnnotations.VERSION to labels.version,
                OciAnnotations.IMAGE_VERSION to labels.version,
            ),
        ),
    )

    private fun descriptor(d: OciDescriptor): Map<String, Any> = linkedMapOf<String, Any>(
        "mediaType" to d.mediaType,
        "digest" to d.digest,
        "size" to d.size,
    ).apply { if (d.annotations.isNotEmpty()) put("annotations", d.annotations.toSortedMap()) }
}

object OciDigest {
    private val SHA256 = Regex("^sha256:[a-f0-9]{64}$")

    fun sha256(bytes: ByteArray): String = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun isSha256(digest: String): Boolean = SHA256.matches(digest)
}

/**
 * One `WWW-Authenticate` challenge (RFC 7235): its scheme and parameters. Registries send one,
 * `Basic realm="…"` or `Bearer realm="…",service="…",scope="…"`; a scope's value holds commas, so
 * values are read quoted first.
 */
data class AuthChallenge(val scheme: String, val params: Map<String, String>) {
    val isBasic: Boolean get() = scheme.equals("Basic", ignoreCase = true)
    val isBearer: Boolean get() = scheme.equals("Bearer", ignoreCase = true)

    companion object {
        private val SCHEME = Regex("""^\s*([A-Za-z][A-Za-z0-9!#$%&'*+.^_`|~-]*)(?:\s+(.*))?$""")
        private val PARAM = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^\s,"]+))""")

        fun parse(header: String?): AuthChallenge? {
            val match = header?.let { SCHEME.matchEntire(it.trim()) } ?: return null
            val params = match.groupValues[2].let { rest ->
                PARAM.findAll(rest).associate { m ->
                    m.groupValues[1].lowercase() to (m.groups[2]?.value?.replace(Regex("""\\(.)"""), "$1") ?: m.groupValues[3])
                }
            }
            return AuthChallenge(match.groupValues[1], params)
        }
    }
}

/** The `next` page of a paginated listing (RFC 8288 `Link: <…>; rel="next"`), resolved against [base]. */
object LinkHeader {
    private val NEXT = Regex("""<([^>]+)>\s*;\s*(?:[^,]*;\s*)*rel="?next"?""", RegexOption.IGNORE_CASE)

    fun next(header: String?, base: URI): URI? = header?.let { NEXT.find(it) }?.groupValues?.get(1)?.let { base.resolve(it) }
}

/** The `tags` of a `/tags/list` answer; a repository with none answers `null`. */
fun parseTagList(mapper: ObjectMapper, bytes: ByteArray): List<String> {
    val root = mapper.readTree(bytes)
    val tags = root.path("tags")
    if (!tags.isArray) return emptyList()
    return (0 until tags.size()).mapNotNull { i -> tags.get(i).takeIf { it.isString }?.stringValue() }
}

/**
 * The release numbers among a repository's tags, highest first. Registries list tags in their own
 * order (lexical, or as pushed), never by number, and a repository may hold tags that are not
 * releases (`latest`); those are left out.
 */
fun releaseVersionsNewestFirst(tags: Collection<String>): List<String> = tags
    .mapNotNull { tag -> SemVer.parseOrNull(tag)?.takeIf { it.toString() == tag }?.let { it to tag } }
    .distinctBy { it.second }
    .sortedByDescending { it.first }
    .map { it.second }

/**
 * Where a catalog's releases live in a registry: `<prefix>/<namespace>/<catalogKey>`, each part a
 * repository path component as the distribution spec names them.
 */
object RegistryRepository {
    private val COMPONENT = Regex("^[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*$")
    private const val MAX_LENGTH = 255

    fun isValidPrefix(prefix: String): Boolean = prefix.isEmpty() || prefix.split('/').all { COMPONENT.matches(it) }

    /** @throws IllegalArgumentException when the parts do not make a repository name. */
    fun of(prefix: String, namespace: String, catalogKey: String): String {
        require(isValidPrefix(prefix)) { "'$prefix' is not a repository prefix" }
        require(COMPONENT.matches(namespace)) { "'$namespace' cannot be part of a repository name" }
        require(COMPONENT.matches(catalogKey)) { "'$catalogKey' cannot be part of a repository name" }
        val name = listOf(prefix, namespace, catalogKey).filter { it.isNotEmpty() }.joinToString("/")
        require(name.length <= MAX_LENGTH) { "repository name '$name' is longer than $MAX_LENGTH characters" }
        return name
    }
}
