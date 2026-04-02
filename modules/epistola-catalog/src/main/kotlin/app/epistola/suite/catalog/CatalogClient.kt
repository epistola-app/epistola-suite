package app.epistola.suite.catalog

import app.epistola.suite.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.protocol.ResourceDetail
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI

@Component
class CatalogClient(
    private val catalogRestClient: RestClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun fetchManifest(url: String, authType: AuthType, credential: String?): CatalogManifest {
        logger.debug("Fetching catalog manifest from {}", url)
        return catalogRestClient.get()
            .uri(url)
            .applyAuth(authType, credential)
            .retrieve()
            .body(CatalogManifest::class.java)
            ?: throw CatalogFetchException("Empty response from catalog manifest: $url")
    }

    fun fetchResourceDetail(detailUrl: String, manifestUrl: String, authType: AuthType, credential: String?): ResourceDetail {
        val resolvedUrl = resolveDetailUrl(detailUrl, manifestUrl)
        logger.debug("Fetching resource detail from {}", resolvedUrl)
        return catalogRestClient.get()
            .uri(resolvedUrl)
            .applyAuth(authType, credential)
            .retrieve()
            .body(ResourceDetail::class.java)
            ?: throw CatalogFetchException("Empty response from resource detail: $resolvedUrl")
    }

    private fun RestClient.RequestHeadersSpec<*>.applyAuth(authType: AuthType, credential: String?): RestClient.RequestHeadersSpec<*> = apply {
        when (authType) {
            AuthType.NONE -> {}
            AuthType.BEARER -> header(HttpHeaders.AUTHORIZATION, "Bearer $credential")
            AuthType.API_KEY -> header("X-API-Key", credential ?: "")
        }
    }

    companion object {
        fun resolveDetailUrl(detailUrl: String, manifestUrl: String): String {
            if (detailUrl.startsWith("http://") || detailUrl.startsWith("https://")) {
                return detailUrl
            }
            val manifestUri = URI.create(manifestUrl)
            return manifestUri.resolve(detailUrl).toString()
        }
    }
}

class CatalogFetchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
