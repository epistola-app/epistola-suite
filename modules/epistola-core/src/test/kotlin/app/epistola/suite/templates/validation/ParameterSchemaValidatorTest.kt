// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class ParameterSchemaValidatorTest {
    private lateinit var validator: ParameterSchemaValidator
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        validator = ParameterSchemaValidator()
        objectMapper = ObjectMapper()
    }

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    @Nested
    inner class HappyPath {
        @Test
        fun `null schema is accepted (means no parameters)`() {
            validator.validate(null)
        }

        @Test
        fun `empty schema with no properties is accepted`() {
            validator.validate(schema("""{"type":"object","properties":{}}"""))
        }

        @Test
        fun `string parameter is accepted`() {
            validator.validate(schema("""{"type":"object","properties":{"name":{"type":"string"}}}"""))
        }

        @Test
        fun `all primitive types are accepted`() {
            validator.validate(
                schema(
                    """
                    {"type":"object","properties":{
                      "s":{"type":"string"},
                      "n":{"type":"number"},
                      "i":{"type":"integer"},
                      "b":{"type":"boolean"},
                      "d":{"type":"string","format":"date"},
                      "dt":{"type":"string","format":"date-time"}
                    }}
                    """.trimIndent(),
                ),
            )
        }

        @Test
        fun `list-of-primitive parameter is accepted`() {
            validator.validate(
                schema("""{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"}}}}"""),
            )
        }

        @Test
        fun `required entries that match declared properties are accepted`() {
            validator.validate(
                schema("""{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}"""),
            )
        }

        @Test
        fun `description and default are accepted on properties`() {
            validator.validate(
                schema(
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Recipient","default":"Anonymous"}
                    }}
                    """.trimIndent(),
                ),
            )
        }

        @Test
        fun `camelCase parameter names are accepted`() {
            validator.validate(
                schema("""{"type":"object","properties":{"recipientName":{"type":"string"}}}"""),
            )
        }
    }

    @Nested
    inner class InvalidShape {
        @Test
        fun `non-object schema is rejected`() {
            assertThatThrownBy { validator.validate(objectMapper.readTree("[]")) }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_SCHEMA_INVALID_TYPE)
        }

        @Test
        fun `schema with type other than 'object' is rejected`() {
            assertThatThrownBy { validator.validate(schema("""{"type":"array"}""")) }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_SCHEMA_INVALID_TYPE)
                .hasMessageContaining("must be 'object'")
        }

        @Test
        fun `properties as a non-object is rejected`() {
            assertThatThrownBy { validator.validate(schema("""{"type":"object","properties":[]}""")) }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_SCHEMA_INVALID_TYPE)
                .hasMessageContaining("'properties' must be an object")
        }

        @Test
        fun `required as a non-array is rejected`() {
            assertThatThrownBy { validator.validate(schema("""{"type":"object","properties":{},"required":"a"}""")) }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_SCHEMA_INVALID_TYPE)
                .hasMessageContaining("'required' must be an array")
        }
    }

    @Nested
    inner class NameRules {
        @Test
        fun `kebab-case name is rejected`() {
            assertThatThrownBy {
                validator.validate(schema("""{"type":"object","properties":{"recipient-name":{"type":"string"}}}"""))
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_NAME_INVALID)
        }

        @Test
        fun `name starting with uppercase is rejected`() {
            assertThatThrownBy {
                validator.validate(schema("""{"type":"object","properties":{"Foo":{"type":"string"}}}"""))
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_NAME_INVALID)
        }

        @Test
        fun `name starting with digit is rejected`() {
            assertThatThrownBy {
                validator.validate(schema("""{"type":"object","properties":{"1foo":{"type":"string"}}}"""))
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_NAME_INVALID)
        }

        @Test
        fun `reserved alias 'params' is rejected as parameter name`() {
            assertThatThrownBy {
                validator.validate(schema("""{"type":"object","properties":{"params":{"type":"string"}}}"""))
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_NAME_RESERVED)
        }

        @Test
        fun `reserved scope names sys and item are rejected`() {
            for (name in listOf("sys", "item", "index")) {
                assertThatThrownBy {
                    validator.validate(schema("""{"type":"object","properties":{"$name":{"type":"string"}}}"""))
                }
                    .isInstanceOf(ValidationException::class.java)
                    .hasValidationCode(ValidationCode.PARAMETER_NAME_RESERVED)
            }
        }

        @Test
        fun `loop suffix names are rejected`() {
            for (name in listOf("foo_index", "bar_first", "baz_last")) {
                assertThatThrownBy {
                    validator.validate(schema("""{"type":"object","properties":{"$name":{"type":"string"}}}"""))
                }
                    .isInstanceOf(ValidationException::class.java)
                    .hasValidationCode(ValidationCode.PARAMETER_NAME_RESERVED)
            }
        }
    }

    @Nested
    inner class TypeRules {
        @Test
        fun `unsupported type 'object' is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema("""{"type":"object","properties":{"x":{"type":"object","properties":{}}}}"""),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_TYPE_UNSUPPORTED)
                .hasMessageContaining("type 'object'")
        }

        @Test
        fun `missing type is rejected`() {
            assertThatThrownBy {
                validator.validate(schema("""{"type":"object","properties":{"x":{}}}"""))
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_TYPE_UNSUPPORTED)
                .hasMessageContaining("missing 'type'")
        }

        @Test
        fun `unsupported string format is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema("""{"type":"object","properties":{"x":{"type":"string","format":"email"}}}"""),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_TYPE_UNSUPPORTED)
                .hasMessageContaining("format 'email'")
        }

        @Test
        fun `array without items is rejected`() {
            assertThatThrownBy {
                validator.validate(schema("""{"type":"object","properties":{"x":{"type":"array"}}}"""))
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_TYPE_UNSUPPORTED)
                .hasMessageContaining("missing 'items'")
        }

        @Test
        fun `array of objects is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema(
                        """
                        {"type":"object","properties":{"x":{"type":"array","items":{"type":"object"}}}}
                        """.trimIndent(),
                    ),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_TYPE_UNSUPPORTED)
        }
    }

    @Nested
    inner class RequiredRules {
        @Test
        fun `required entry referencing undeclared property is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema("""{"type":"object","properties":{"a":{"type":"string"}},"required":["a","b"]}"""),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_REQUIRED_UNKNOWN)
                .hasMessageContaining("'b'")
        }
    }

    @Nested
    inner class DefaultTypeMatching {
        @Test
        fun `string default with non-string value is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema("""{"type":"object","properties":{"x":{"type":"string","default":42}}}"""),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_DEFAULT_TYPE_MISMATCH)
        }

        @Test
        fun `boolean default with non-boolean value is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema("""{"type":"object","properties":{"x":{"type":"boolean","default":"yes"}}}"""),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_DEFAULT_TYPE_MISMATCH)
        }

        @Test
        fun `array default that is not an array is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema(
                        """
                        {"type":"object","properties":{"x":{"type":"array","items":{"type":"string"},"default":"oops"}}}
                        """.trimIndent(),
                    ),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_DEFAULT_TYPE_MISMATCH)
        }

        @Test
        fun `array default with wrong item types is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema(
                        """
                        {"type":"object","properties":{"x":{"type":"array","items":{"type":"integer"},"default":[1,"oops",3]}}}
                        """.trimIndent(),
                    ),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_DEFAULT_TYPE_MISMATCH)
        }

        @Test
        fun `array default with matching item types is accepted`() {
            validator.validate(
                schema(
                    """
                    {"type":"object","properties":{"x":{"type":"array","items":{"type":"integer"},"default":[1,2,3]}}}
                    """.trimIndent(),
                ),
            )
        }

        @Test
        fun `integer default with floating point value is rejected`() {
            assertThatThrownBy {
                validator.validate(
                    schema("""{"type":"object","properties":{"x":{"type":"integer","default":1.5}}}"""),
                )
            }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.PARAMETER_DEFAULT_TYPE_MISMATCH)
        }
    }

    @Nested
    inner class FieldPath {
        @Test
        fun `error message field includes the prefix path`() {
            val ex = catchValidationException {
                validator.validate(
                    schema("""{"type":"object","properties":{"recipient-name":{"type":"string"}}}"""),
                    fieldPrefix = "stencilDraft.parameterSchema",
                )
            }
            assertThat(ex.field).startsWith("stencilDraft.parameterSchema")
        }
    }

    private fun catchValidationException(block: () -> Unit): ValidationException {
        try {
            block()
            error("expected ValidationException")
        } catch (e: ValidationException) {
            return e
        }
    }
}
