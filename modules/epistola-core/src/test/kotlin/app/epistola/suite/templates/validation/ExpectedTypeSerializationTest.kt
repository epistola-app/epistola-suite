package app.epistola.suite.templates.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class ExpectedTypeSerializationTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `fromValue is case-insensitive and falls back to UNKNOWN`() {
        assertThat(ExpectedType.fromValue("string")).isEqualTo(ExpectedType.STRING)
        assertThat(ExpectedType.fromValue("INTEGER")).isEqualTo(ExpectedType.INTEGER)
        assertThat(ExpectedType.fromValue("Date")).isEqualTo(ExpectedType.DATE)
        assertThat(ExpectedType.fromValue(null)).isEqualTo(ExpectedType.UNKNOWN)
        assertThat(ExpectedType.fromValue("custom-type")).isEqualTo(ExpectedType.UNKNOWN)
    }

    @Test
    fun `jackson serializes and deserializes expectedType as lowercase wire value`() {
        val json = objectMapper.writeValueAsString(mapOf("expectedType" to ExpectedType.BOOLEAN))

        assertThat(json).contains("\"expectedType\":\"boolean\"")

        val parsed = objectMapper.readValue("\"number\"", ExpectedType::class.java)

        assertThat(parsed).isEqualTo(ExpectedType.NUMBER)
    }
}
