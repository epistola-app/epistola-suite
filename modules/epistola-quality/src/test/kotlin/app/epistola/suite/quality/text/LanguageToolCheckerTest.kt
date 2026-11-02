// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.i18n.LocaleSource
import app.epistola.suite.i18n.ResolvedLocale
import app.epistola.suite.quality.CheckTrigger
import app.epistola.suite.quality.LanguageToolProperties
import app.epistola.suite.quality.QualityProperties
import app.epistola.suite.quality.QualitySeverity
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The adapter against a stand-in LanguageTool serving responses recorded from a real LanguageTool 6.8
 * (`src/test/resources/languagetool/`), for exactly the request the adapter builds: so the offsets it
 * maps back are LanguageTool's own.
 */
class LanguageToolCheckerTest {
    private val objectMapper = JsonMapper.builder().build()
    private val checks = CopyOnWriteArrayList<Map<String, String>>()
    private var checkResponse: String = fixture("check-nl.json")
    private var checkStatus = 200
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v2/languages") { exchange ->
            exchange.respond(200, fixture("languages.json"))
        }
        createContext("/v2/check") { exchange ->
            val form = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8).split('&').associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(pair.substringAfter('='), StandardCharsets.UTF_8)
            }
            val probe = form["data"] == """{"annotation":[{"text":"a"}]}"""
            if (!probe) checks += form
            exchange.respond(if (probe) 200 else checkStatus, if (probe) """{"software":{"version":"6.8"},"matches":[]}""" else checkResponse)
        }
        start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun checker(url: String? = "http://127.0.0.1:${server.address.port}", maxRequestCharacters: Int = 20_000) = LanguageToolChecker(
        QualityProperties(languageTool = LanguageToolProperties(url = url, maxRequestCharacters = maxRequestCharacters)),
        objectMapper,
    )

    private val dutch = ResolvedLocale("nl-NL", LocaleSource.VARIANT)

    @Test
    fun `without a URL it is skipped, as it is with a blank one`() {
        assertThat(checker(url = null).readiness(dutch)).isInstanceOf(TextCheckerReadiness.Skipped::class.java)
        assertThat((checker(url = "  ").readiness(dutch) as TextCheckerReadiness.Skipped).reason).contains("not configured")
    }

    @Test
    fun `an installation provider each tenant switches on, on save, Check now and ready by default`() {
        val checker = checker()

        assertThat(checker.egress).isEqualTo(TextEgress.INSTALLATION)
        assertThat(checker.configured).isTrue()
        assertThat(checker(url = null).configured).isFalse()
        assertThat(checker.switchable).isTrue()
        assertThat(checker.triggers).containsExactlyInAnyOrder(CheckTrigger.SAVE, CheckTrigger.EXPLICIT, CheckTrigger.READY)
    }

    @Test
    fun `it does not guess a language nobody chose`() {
        val readiness = checker().readiness(ResolvedLocale("en-US", LocaleSource.INSTALLATION))

        assertThat((readiness as TextCheckerReadiness.Skipped).reason).contains("no language")
    }

    @Test
    fun `ready under the server's version, the level and the language it will ask for`() {
        assertThat(checker().readiness(dutch)).isEqualTo(TextCheckerReadiness.Ready("languagetool/6.8/default/nl-NL"))
        // A tag the server lacks falls back to its language alone, and one it has neither of is skipped.
        assertThat(checker().readiness(ResolvedLocale("en-NZ", LocaleSource.TENANT))).isEqualTo(TextCheckerReadiness.Ready("languagetool/6.8/default/en"))
        assertThat(checker().readiness(ResolvedLocale("fr-FR", LocaleSource.TENANT))).isInstanceOf(TextCheckerReadiness.Skipped::class.java)
    }

    @Test
    fun `an unreachable server makes it unavailable rather than skipped`() {
        server.stop(0)

        assertThatThrownBy { checker().readiness(dutch) }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `blocks go in one request as annotated text, placeholders as markup read as X and nothing else`() {
        val texts = listOf("Beste ￼, dit is een tekts.", "Wij sturen u de de stukken.")

        checker().check(texts, dutch, "languagetool/6.8/default/nl-NL")

        val request = checks.single()
        assertThat(request["language"]).isEqualTo("nl-NL")
        assertThat(objectMapper.readTree(request["data"])).isEqualTo(
            objectMapper.readTree(
                """
                {"annotation":[
                  {"text":"Beste "},{"markup":"￼","interpretAs":"X"},{"text":", dit is een tekts."},
                  {"text":"\n\n"},
                  {"text":"Wij sturen u de de stukken."}
                ]}
                """,
            ),
        )
    }

    @Test
    fun `LanguageTool's offsets are mapped back to each block`() {
        val texts = listOf("Beste ￼, dit is een tekts.", "Wij sturen u de de stukken.")

        val answers = checker().check(texts, dutch, "languagetool/6.8/default/nl-NL")

        val typo = answers[0].single()
        assertThat(texts[0].substring(typo.offset, typo.offset + typo.length)).isEqualTo("tekts")
        assertThat(typo.ruleId).isEqualTo("MORFOLOGIK_RULE_NL_NL")
        assertThat(typo.category).isEqualTo(TextCategory.SPELLING)
        assertThat(typo.severity).isEqualTo(QualitySeverity.WARNING)
        assertThat(typo.suggestions).hasSizeLessThanOrEqualTo(5).startsWith("tekst")

        val repeat = answers[1].single()
        assertThat(texts[1].substring(repeat.offset, repeat.offset + repeat.length)).isEqualTo("de de")
        assertThat(repeat.suggestions).containsExactly("de")
        // LanguageTool's own rule description, as the explanation until a profile rule supplies one.
        assertThat(typo.explanation).isNotBlank()
    }

    @Test
    fun `more text than one request carries is split over several`() {
        checkResponse = """{"matches":[]}"""
        val texts = listOf("a".repeat(30), "b".repeat(30), "c".repeat(30))

        val answers = checker(maxRequestCharacters = 50).check(texts, dutch, "languagetool/6.8/default/nl-NL")

        assertThat(checks).hasSize(3)
        assertThat(answers).containsExactly(emptyList(), emptyList(), emptyList())
    }

    @Test
    fun `an error from the server is a failure, not an empty answer`() {
        checkStatus = 500

        assertThatThrownBy { checker().check(listOf("Hallo."), dutch, "languagetool/6.8/default/nl-NL") }.isInstanceOf(Exception::class.java)
    }

    private fun com.sun.net.httpserver.HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/languagetool/$name")) { "no fixture $name" }.readText()
}
