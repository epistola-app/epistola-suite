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
class RichTextBlockSchemaTest {

    private val objectMapper = ObjectMapper()
    private val validator = JsonSchemaValidator(objectMapper)

    private val contractSchema: ObjectNode = objectMapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "bio": { "${"$"}ref": "https://epistola.app/schemas/richtext-block-v1.json" }
          }
        }
        """.trimIndent(),
    ) as ObjectNode

    private fun dataWithBio(bioJson: String): ObjectNode = objectMapper.readTree(
        """{ "bio": $bioJson }""",
    ) as ObjectNode

    @Test
    fun `accepts a minimal valid doc`() {
        val data = dataWithBio(
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
    fun `accepts text with strong and link marks`() {
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "paragraph",
                  "content": [
                    { "type": "text", "text": "Bold ", "marks": [{ "type": "strong" }] },
                    { "type": "text", "text": "link", "marks": [{ "type": "link", "attrs": { "href": "https://example.com" } }] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isEmpty()
    }

    @Test
    fun `accepts bullet and ordered lists`() {
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "bullet_list",
                  "content": [
                    { "type": "list_item", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "one" }] }] }
                  ]
                },
                {
                  "type": "ordered_list",
                  "attrs": { "listType": "decimal" },
                  "content": [
                    { "type": "list_item", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "two" }] }] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isEmpty()
    }

    @Test
    fun `accepts a nested bullet list inside a list item`() {
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "bullet_list",
                  "content": [
                    {
                      "type": "list_item",
                      "content": [
                        { "type": "paragraph", "content": [{ "type": "text", "text": "parent" }] },
                        {
                          "type": "bullet_list",
                          "content": [
                            { "type": "list_item", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "child" }] }] }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isEmpty()
    }

    @Test
    fun `accepts a nested ordered list inside a list item`() {
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "ordered_list",
                  "content": [
                    {
                      "type": "list_item",
                      "content": [
                        { "type": "paragraph", "content": [{ "type": "text", "text": "parent" }] },
                        {
                          "type": "ordered_list",
                          "attrs": { "listType": "lower-alpha" },
                          "content": [
                            { "type": "list_item", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "child" }] }] }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isEmpty()
    }

    @Test
    fun `rejects a list item whose first child is not a paragraph`() {
        // The editor's list item content model is 'paragraph block*': nested
        // lists may only follow a leading paragraph, never open the item.
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "bullet_list",
                  "content": [
                    {
                      "type": "list_item",
                      "content": [
                        {
                          "type": "bullet_list",
                          "content": [
                            { "type": "list_item", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "child" }] }] }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects a list item with empty content`() {
        // paragraph block* requires a leading paragraph; an empty list item is
        // not a valid document (the editor never produces one).
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "bullet_list",
                  "content": [
                    { "type": "list_item", "content": [] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects heading inside a list item`() {
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "bullet_list",
                  "content": [
                    {
                      "type": "list_item",
                      "content": [
                        { "type": "paragraph", "content": [{ "type": "text", "text": "parent" }] },
                        { "type": "heading", "attrs": { "level": 2 }, "content": [{ "type": "text", "text": "Title" }] }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects expression nodes inside a nested list`() {
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "bullet_list",
                  "content": [
                    {
                      "type": "list_item",
                      "content": [
                        { "type": "paragraph", "content": [{ "type": "text", "text": "parent" }] },
                        {
                          "type": "bullet_list",
                          "content": [
                            {
                              "type": "list_item",
                              "content": [
                                { "type": "paragraph", "content": [{ "type": "expression", "attrs": { "expression": "customer.name" } }] }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `accepts multiple paragraphs`() {
        val data = dataWithBio(
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
        assertThat(validator.validate(contractSchema, data)).isEmpty()
    }

    @Test
    fun `rejects non-doc top-level type`() {
        val data = dataWithBio("""{ "type": "paragraph", "content": [] }""")
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects heading nodes`() {
        val data = dataWithBio(
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
    fun `rejects expression nodes (reserved for phase 2)`() {
        val data = dataWithBio(
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
        val data = dataWithBio(
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

    @Test
    fun `rejects link without href`() {
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "paragraph",
                  "content": [
                    { "type": "text", "text": "x", "marks": [{ "type": "link", "attrs": {} }] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }

    @Test
    fun `rejects textStyle color that is not a hex value`() {
        val data = dataWithBio(
            """
            {
              "type": "doc",
              "content": [
                {
                  "type": "paragraph",
                  "content": [
                    { "type": "text", "text": "x", "marks": [{ "type": "textStyle", "attrs": { "color": "red" } }] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertThat(validator.validate(contractSchema, data)).isNotEmpty()
    }
}
