package app.epistola.suite.versioncheck

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

@Component
class VersionCheckClient(
    @Qualifier(VersionCheckConfiguration.VERSION_CHECK_REST_CLIENT)
    private val restClient: RestClient,
) {
    fun fetch(url: String, currentVersion: String): EpistolaReleasesDocument = try {
        restClient.get()
            .uri(url)
            .header("User-Agent", "epistola-suite/$currentVersion")
            .header("X-Epistola-Suite-Version", currentVersion)
            .retrieve()
            .body(EpistolaReleasesDocument::class.java)
            ?: throw VersionCheckException("Empty response from $url")
    } catch (e: RestClientException) {
        throw VersionCheckException("Fetch failed for $url: ${e.message}", e)
    }
}

class VersionCheckException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
