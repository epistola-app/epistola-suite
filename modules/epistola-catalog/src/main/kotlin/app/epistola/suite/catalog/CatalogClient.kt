package app.epistola.suite.catalog

import app.epistola.suite.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.protocol.ResourceDetail
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Path

@Component
class CatalogClient(
    private val catalogRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val resourceLoader: ResourceLoader,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun fetchManifest(url: String, authType: AuthType, credential: String?): CatalogManifest {
        validateUrl(url)
        logger.debug("Fetching catalog manifest from {}", url)
        return readLocal(url, CatalogManifest::class.java)
            ?: fetchHttp(url, authType, credential)
    }

    fun fetchResourceDetail(detailUrl: String, manifestUrl: String, authType: AuthType, credential: String?): ResourceDetail {
        val resolvedUrl = resolveDetailUrl(detailUrl, manifestUrl)
        validateUrl(resolvedUrl)
        logger.debug("Fetching resource detail from {}", resolvedUrl)
        return readLocal(resolvedUrl, ResourceDetail::class.java)
            ?: fetchHttp(resolvedUrl, authType, credential)
    }

    private fun <T> readLocal(url: String, type: Class<T>): T? = when {
        url.startsWith("file:") -> readFile(url, type)
        url.startsWith("classpath:") -> readClasspath(url, type)
        else -> null
    }

    private fun <T> readFile(fileUrl: String, type: Class<T>): T {
        val path = Path.of(URI.create(fileUrl))
        if (!path.toFile().exists()) {
            throw CatalogFetchException("File not found: $path")
        }
        return objectMapper.readValue(path.toFile(), type)
    }

    private fun <T> readClasspath(classpathUrl: String, type: Class<T>): T {
        val resource = resourceLoader.getResource(classpathUrl)
        if (!resource.exists()) {
            throw CatalogFetchException("Classpath resource not found: $classpathUrl")
        }
        return objectMapper.readValue(resource.contentAsByteArray, type)
    }

    private inline fun <reified T : Any> fetchHttp(url: String, authType: AuthType, credential: String?): T = catalogRestClient.get()
        .uri(url)
        .applyAuth(authType, credential)
        .retrieve()
        .body(T::class.java)
        ?: throw CatalogFetchException("Empty response from: $url")

    private fun RestClient.RequestHeadersSpec<*>.applyAuth(authType: AuthType, credential: String?): RestClient.RequestHeadersSpec<*> = apply {
        when (authType) {
            AuthType.NONE -> {}
            AuthType.BEARER -> header(HttpHeaders.AUTHORIZATION, "Bearer $credential")
            AuthType.API_KEY -> header("X-API-Key", credential ?: "")
        }
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https", "file", "classpath")

        fun validateUrl(url: String) {
            require(url.substringAfterLast(".").equals("json", ignoreCase = true)) {
                "Catalog URLs must point to .json files"
            }
            val scheme = url.substringBefore(":")
            require(scheme in ALLOWED_SCHEMES) {
                "Unsupported URL scheme: $scheme. Allowed: $ALLOWED_SCHEMES"
            }
            if (url.startsWith("file:")) {
                val path = Path.of(URI.create(url)).normalize().toString()
                require(!path.contains("..")) { "Path traversal not allowed" }
            }
        }

        fun resolveDetailUrl(detailUrl: String, manifestUrl: String): String {
            if (detailUrl.startsWith("http://") ||
                detailUrl.startsWith("https://") ||
                detailUrl.startsWith("file:") ||
                detailUrl.startsWith("classpath:")
            ) {
                return detailUrl
            }
            // For classpath: URLs, resolve relative paths by replacing the last segment
            if (manifestUrl.startsWith("classpath:")) {
                val basePath = manifestUrl.substringAfter("classpath:").substringBeforeLast("/")
                val resolved = "$basePath/$detailUrl".replace("/./", "/")
                return "classpath:$resolved"
            }
            val manifestUri = URI.create(manifestUrl)
            return manifestUri.resolve(detailUrl).toString()
        }
    }
}

class CatalogFetchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
