package app.epistola.suite.versioncheck

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

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
    } catch (e: RestClientResponseException) {
        if (e.statusCode == HttpStatus.NOT_FOUND) {
            throw VersionMetadataUnavailableException("Release metadata not found at $url", e)
        }
        throw VersionCheckException("Fetch failed for $url: ${e.message}", e)
    } catch (e: RestClientException) {
        throw VersionCheckException("Fetch failed for $url: ${e.message}", e)
    }
}

class VersionCheckException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class VersionMetadataUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
