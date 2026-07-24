// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Tag("unit")
class RichTextInlineSchemaTest {

    private val objectMapper = ObjectMapper()
    private val validator = JsonSchemaValidator(objectMapper)

    private val contractSchema: ObjectNode = objectMapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "greeting": { "${"$"}ref": "https://epistola.app/schemas/richtext-inline-v1.json" }
          }
        }
        """.trimIndent(),
    ) as ObjectNode

    private fun dataWithGreeting(json: String): ObjectNode = objectMapper.readTree(
        """{ "greeting": $json }""",
    ) as ObjectNode

    @Test
    fun `accepts a single-paragraph doc with plain text`() {
        val data = dataWithGreeting(
            """
            {
              "type": "doc",
              "content": [
                { "type": "paragraph", "content": [{ "type": "text", "text": "Hello" }] }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isEmpty()
    }

    @Test
    fun `accepts text with marks (strong, em, link, color)`() {
        val data = dataWithGreeting(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "paragraph",
                  "content": [
                    { "type": "text", "text": "Hi ", "marks": [{ "type": "strong" }] },
                    { "type": "text", "text": "John", "marks": [{ "type": "em" }, { "type": "textStyle", "attrs": { "color": "#FF0000" } }] },
                    { "type": "text", "text": " — ", "marks": [] },
                    { "type": "text", "text": "site", "marks": [{ "type": "link", "attrs": { "href": "https://example.com" } }] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isEmpty()
    }

    @Test
    fun `accepts a hard break inside the paragraph`() {
        val data = dataWithGreeting(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "paragraph",
                  "content": [
                    { "type": "text", "text": "line 1" },
                    { "type": "hard_break" },
                    { "type": "text", "text": "line 2" }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isEmpty()
    }

    @Test
    fun `rejects multiple paragraphs`() {
        val data = dataWithGreeting(
            """
            {
              "type": "doc",
              "content": [
                { "type": "paragraph", "content": [{ "type": "text", "text": "first" }] },
                { "type": "paragraph", "content": [{ "type": "text", "text": "second" }] }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects empty content array`() {
        val data = dataWithGreeting(
            """{ "type": "doc", "content": [] }""",
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects bullet list at top level`() {
        val data = dataWithGreeting(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "bullet_list",
                  "content": [
                    { "type": "list_item", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "x" }] }] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects heading at top level`() {
        val data = dataWithGreeting(
            """
            {
              "type": "doc",
              "content": [
                { "type": "heading", "attrs": { "level": 1 }, "content": [{ "type": "text", "text": "Title" }] }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects expression node inside paragraph`() {
        val data = dataWithGreeting(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "paragraph",
                  "content": [
                    { "type": "expression", "attrs": { "expression": "customer.name" } }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects unknown mark`() {
        val data = dataWithGreeting(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "paragraph",
                  "content": [
                    { "type": "text", "text": "x", "marks": [{ "type": "highlight" }] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }
}
