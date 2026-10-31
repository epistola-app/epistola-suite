// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.i18n.ResolvedLocale
import app.epistola.suite.quality.LanguageToolProperties
import app.epistola.suite.quality.QualityProperties
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Spelling and grammar from a LanguageTool server the operator runs (`epistola.quality.language-tool.url`),
 * through its HTTP API, `POST /v2/check`.
 *
 * Blocks are sent as LanguageTool's *annotated text*: literal text as text, each placeholder as markup
 * LanguageTool reads as the neutral word `X`, so "Dear {name}," is checked as "Dear X," and the
 * expression's value, or even its name, never leaves the installation. Offsets in LanguageTool's answer
 * count the markup, so they are offsets in [TextBlock.text] as they are.
 *
 * Several blocks go in one request, separated by blank lines, so a variant is a request or two, not one
 * per paragraph.
 *
 * Runs only for a language someone chose, for the template (`system.locale`) or for the tenant: checked
 * in the installation's default instead, a Dutch template would be flagged word by word as misspelt
 * English.
 */
@Component
class LanguageToolChecker(
    properties: QualityProperties,
    private val objectMapper: ObjectMapper,
) : TextChecker {
    private val settings: LanguageToolProperties = properties.languageTool
    private val baseUrl: String? = settings.configuredUrl

    private val client: RestClient? = baseUrl?.let { url ->
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(settings.connectTimeout).build())
                    .apply { setReadTimeout(settings.readTimeout) },
            )
            .build()
    }

    /** What the server is: learned once, then again after [SERVER_INFO_TTL_NANOS], so an upgrade reaches the cache key. */
    @Volatile
    private var server: ServerInfo? = null

    override val checkerId = QualitySourceId("languagetool")

    override val displayName = "LanguageTool"

    override val egress = TextEgress.INSTALLATION

    override fun readiness(locale: ResolvedLocale): TextCheckerReadiness {
        if (client == null) return TextCheckerReadiness.Skipped("not configured on this installation")
        if (!locale.chosen) return TextCheckerReadiness.Skipped("no language is set for this template or its tenant")
        val info = serverInfo(client)
        val language = info.languageFor(locale.tag)
            ?: return TextCheckerReadiness.Skipped("this LanguageTool server does not check ${locale.tag}")
        return TextCheckerReadiness.Ready("languagetool/${info.version}/${settings.level}/$language")
    }

    override fun check(
        texts: List<String>,
        locale: ResolvedLocale,
        configuration: String,
    ): List<List<TextDiagnostic>> {
        val client = checkNotNull(client) { "LanguageTool is not configured" }
        val language = configuration.substringAfterLast('/')
        val results = MutableList(texts.size) { emptyList<TextDiagnostic>() }
        for (batch in batches(texts)) {
            val response = post(client, language, annotation(batch.map { texts[it] }))
            val starts = batch.runningFold(0) { start, index -> start + texts[index].length + SEPARATOR.length }
            val byText = response.path("matches").mapNotNull { match ->
                val offset = match.path("offset").asInt(-1)
                val length = match.path("length").asInt(0)
                val position = starts.indexOfLast { it <= offset }.takeIf { it in batch.indices } ?: return@mapNotNull null
                val local = offset - starts[position]
                // A match reaching into the separator spans two blocks; it belongs to neither.
                if (local + length > texts[batch[position]].length) return@mapNotNull null
                batch[position] to diagnostic(match, local, length)
            }.groupBy({ it.first }, { it.second })
            byText.forEach { (index, diagnostics) -> results[index] = diagnostics }
        }
        return results
    }

    /** Indices of [texts], grouped so no request carries much more than the configured characters. */
    private fun batches(texts: List<String>): List<List<Int>> {
        val batches = mutableListOf<MutableList<Int>>()
        var size = 0
        texts.forEachIndexed { index, text ->
            if (batches.isEmpty() || size + text.length > settings.maxRequestCharacters) {
                batches += mutableListOf<Int>()
                size = 0
            }
            batches.last() += index
            size += text.length + SEPARATOR.length
        }
        return batches
    }

    private fun annotation(texts: List<String>): String {
        val items = mutableListOf<Map<String, String>>()
        texts.forEachIndexed { index, text ->
            if (index > 0) items += mapOf("text" to SEPARATOR)
            val literals = text.split(TextBlock.PLACEHOLDER)
            literals.forEachIndexed { i, literal ->
                if (i > 0) items += mapOf("markup" to TextBlock.PLACEHOLDER.toString(), "interpretAs" to PLACEHOLDER_READS_AS)
                if (literal.isNotEmpty()) items += mapOf("text" to literal)
            }
        }
        return objectMapper.writeValueAsString(mapOf("annotation" to items))
    }

    private fun post(client: RestClient, language: String, data: String): JsonNode {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("language", language)
            add("data", data)
            if (settings.level != "default") add("level", settings.level)
        }
        val body = client.post().uri("/v2/check")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body<String>()
        return objectMapper.readTree(checkNotNull(body) { "LanguageTool answered with no body" })
    }

    private fun diagnostic(match: JsonNode, offset: Int, length: Int): TextDiagnostic {
        val rule = match.path("rule")
        val category = category(rule.path("issueType").asString(""), rule.path("category").path("id").asString(""))
        return TextDiagnostic(
            ruleId = rule.path("id").asString("unknown"),
            category = category,
            severity = if (category == TextCategory.SPELLING || category == TextCategory.GRAMMAR) QualitySeverity.WARNING else QualitySeverity.INFO,
            message = match.path("message").asString(""),
            offset = offset,
            length = length,
            suggestions = match.path("replacements").take(MAX_SUGGESTIONS).map { it.path("value").asString("") },
            docsUrl = rule.path("urls").firstOrNull()?.path("value")?.asString(null),
        )
    }

    private fun serverInfo(client: RestClient): ServerInfo {
        server?.takeIf { System.nanoTime() - it.fetchedAt < SERVER_INFO_TTL_NANOS }?.let { return it }
        val languages = objectMapper.readTree(checkNotNull(client.get().uri("/v2/languages").retrieve().body<String>()))
            .mapNotNull { it.path("longCode").asString(null) }
            .toSet()
        // The version is in every check response; a one-character check is the cheapest way to read it.
        val probe = post(client, languages.firstOrNull() ?: "en-US", objectMapper.writeValueAsString(mapOf("annotation" to listOf(mapOf("text" to "a")))))
        val version = probe.path("software").path("version").asString("unknown")
        return ServerInfo(version, languages, System.nanoTime()).also { server = it }
    }

    private data class ServerInfo(
        val version: String,
        val languages: Set<String>,
        val fetchedAt: Long,
    ) {
        /** The code to ask for: the tag itself, else its language alone; null when the server has neither. */
        fun languageFor(tag: String): String? {
            languages.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
            val language = Locale.forLanguageTag(tag).language
            return languages.firstOrNull { it.equals(language, ignoreCase = true) }
        }
    }

    private companion object {
        const val SEPARATOR = "\n\n"
        const val PLACEHOLDER_READS_AS = "X"
        const val MAX_SUGGESTIONS = 5
        val SERVER_INFO_TTL_NANOS = TimeUnit.MINUTES.toNanos(10)

        fun category(issueType: String, categoryId: String): TextCategory = when {
            issueType == "misspelling" -> TextCategory.SPELLING
            issueType == "grammar" || issueType == "duplication" -> TextCategory.GRAMMAR
            categoryId == "PUNCTUATION" || issueType == "typographical" -> TextCategory.PUNCTUATION
            issueType == "whitespace" -> TextCategory.TYPOGRAPHY
            issueType in setOf("style", "register", "locale-violation", "inconsistency") -> TextCategory.STYLE
            else -> TextCategory.OTHER
        }
    }
}
