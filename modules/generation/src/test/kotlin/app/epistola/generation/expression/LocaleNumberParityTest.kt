package app.epistola.generation.expression

import com.dashjoin.jsonata.json.Json
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-language parity for `$formatLocaleNumber`.
 *
 * The function is implemented twice — here in Kotlin (Java `DecimalFormat`) and
 * in the editor's `resolve-expression.ts` (`Intl.NumberFormat` + a picture
 * parser) — and the two MUST agree so the editor preview matches the generated
 * PDF (WYSIWYG). The golden table that pins that contract lives in
 * `src/test/resources/locale-number-parity.json` and is consumed by BOTH this
 * test and the vitest suite (`resolve-expression.test.ts`), so the same
 * `(value, picture, locale)` inputs are asserted against the same expected
 * output on both sides. Add a row → both languages are held to it.
 */
class LocaleNumberParityTest {

    private data class ParityCase(
        val value: Number,
        val picture: String,
        val locale: String,
        val expected: String,
    )

    @Suppress("UNCHECKED_CAST")
    private fun loadCases(): List<ParityCase> {
        val json = javaClass.getResourceAsStream("/locale-number-parity.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Missing /locale-number-parity.json on the test classpath")
        val rows = Json.parseJson(json) as List<Map<String, Any?>>
        return rows.map { row ->
            ParityCase(
                value = row["value"] as Number,
                picture = row["picture"] as String,
                locale = row["locale"] as String,
                expected = row["expected"] as String,
            )
        }
    }

    @Test
    fun `every parity row matches the Java formatter`() {
        val cases = loadCases()
        assertTrue(cases.isNotEmpty(), "Parity fixture is empty")

        cases.forEach { case ->
            val evaluator = JsonataEvaluator(locale = Locale.forLanguageTag(case.locale))
            val actual = evaluator.evaluate(
                "\$formatLocaleNumber(v, '${case.picture}')",
                mapOf("v" to case.value),
            )
            assertEquals(
                case.expected,
                actual,
                "formatLocaleNumber(${case.value}, '${case.picture}', '${case.locale}')",
            )
        }
    }
}
