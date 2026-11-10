// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.expression

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What one expression may cost. A template's expressions run inside the suite (an editor preview
 * renders whatever model the author sends), so an expression that would run for hours or blow the
 * stack is refused with a message naming it, instead of tying up a thread or rendering as empty.
 */
class JsonataLimitsTest {
    private val evaluator = JsonataEvaluator()

    private fun eval(expression: String, data: Map<String, Any?> = emptyMap()) = evaluator.evaluate(expression, data)

    @Test
    fun `pad works as JSONata's up to the width limit`() {
        assertEquals("foo  ", eval("\$pad('foo', 5)"))
        assertEquals("  foo", eval("\$pad('foo', -5)"))
        assertEquals("##foo", eval("\$pad('foo', -5, '#')"))
        assertEquals("foo-+-+-", eval("\$pad('foo', 8, '-+')"))
        assertEquals("foo", eval("\$pad('foo', 2)"))
        assertEquals("00042", eval("\$pad(\$string(n), -5, '0')", mapOf("n" to 42)))
        assertEquals("é😀xx", eval("\$pad('é😀', 4, 'x')"))
        assertEquals(100, (eval("\$pad('', 100, 'x')") as String).length)
        assertEquals(null, eval("\$pad(missing, 5)"))
    }

    @Test
    fun `a pad wider than the limit is refused, naming the expression`() {
        val error = assertFailsWith<IllegalArgumentException> { eval("\$pad('x', 101)") }
        assertContains(error.message!!, "\$pad('x', 101)")
        assertContains(error.message!!, "100")
        assertFailsWith<IllegalArgumentException> { eval("\$pad('x', -101)") }
        assertFailsWith<IllegalArgumentException> { eval("\$pad('x', width)", mapOf("width" to 5000)) }
    }

    @Test
    fun `a huge pad is refused at once, not computed for hours`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertFailsWith<IllegalArgumentException> { eval("\$pad('', 50000000, 'x')") }
        }
    }

    @Test
    fun `recursion past the depth limit is refused, naming the expression`() {
        val expression = "(\$f := function(\$n) { \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(10000))"
        val error = assertFailsWith<IllegalArgumentException> { eval(expression) }
        assertContains(error.message!!, "\$f(10000)")
    }

    @Test
    fun `an expression nested too deeply to parse is refused, not a stack overflow`() {
        val expression = "(".repeat(50_000) + "1" + ")".repeat(50_000)
        assertFailsWith<IllegalArgumentException> { eval(expression) }
    }

    @Test
    fun `other expression errors still render as empty`() {
        assertEquals(null, eval("\$unknownFunction(1)"))
        assertEquals(null, eval("1 +"))
    }
}
