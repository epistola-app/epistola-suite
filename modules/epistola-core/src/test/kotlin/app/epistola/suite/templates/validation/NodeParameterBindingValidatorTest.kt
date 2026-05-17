package app.epistola.suite.templates.validation

import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NodeParameterBindingValidatorTest {
    private lateinit var validator: NodeParameterBindingValidator

    @BeforeEach
    fun setUp() {
        // We give the validator a registry containing a single dispatcher
        // that resolves schemas off a `parameterSchemaSnapshot` prop on
        // *any* node type — independent of the production stencil-specific
        // wiring. That makes these tests focus purely on the validator's
        // own logic.
        val registry = NodeParameterSchemaProviderRegistry(
            listOf(
                NodeTypeProviderBinding(
                    nodeType = "stencil",
                    provider = NodeParameterSchemaProvider { node, _ ->
                        @Suppress("UNCHECKED_CAST")
                        node.props?.get("parameterSchemaSnapshot") as? Map<String, Any?>
                    },
                ),
            ),
        )
        validator = NodeParameterBindingValidator(registry)
    }

    private fun doc(vararg nodes: Node): TemplateDocument {
        val rootSlot = Slot("s-root", "root", "children", nodes.map { it.id })
        return TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node("root", "root", listOf("s-root"))) + nodes.associateBy { it.id },
            slots = mapOf("s-root" to rootSlot),
        )
    }

    private fun catchValidationException(block: () -> Unit): ValidationException {
        try {
            block()
            error("expected ValidationException")
        } catch (e: ValidationException) {
            return e
        }
    }

    private fun stencilNode(
        id: String,
        schema: Map<String, Any?>?,
        bindings: Map<String, Any?>? = null,
    ): Node {
        val props = mutableMapOf<String, Any?>("stencilId" to "x", "version" to 1)
        if (schema != null) props["parameterSchemaSnapshot"] = schema
        if (bindings != null) props["parameterBindings"] = bindings
        return Node(id = id, type = "stencil", props = props)
    }

    @Test
    fun `node without schema is skipped`() {
        // No schema resolver match → silently skipped.
        val node = stencilNode("s1", schema = null, bindings = mapOf("foo" to "customer.name"))
        assertThatCode { validator.validate(doc(node)) }.doesNotThrowAnyException()
    }

    @Test
    fun `binding to a declared parameter is accepted`() {
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("name" to "customer.name"),
        )
        assertThatCode { validator.validate(doc(node)) }.doesNotThrowAnyException()
    }

    @Test
    fun `binding to an undeclared parameter is rejected`() {
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("ghost" to "customer.something"),
        )
        assertThatThrownBy { validator.validate(doc(node)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("NODE_PARAMETER_BINDING_UNKNOWN")
            .hasMessageContaining("'ghost'")
    }

    @Test
    fun `required parameter with binding is accepted`() {
        val node = stencilNode(
            "s1",
            schema = mapOf(
                "properties" to mapOf("name" to mapOf("type" to "string")),
                "required" to listOf("name"),
            ),
            bindings = mapOf("name" to "customer.name"),
        )
        assertThatCode { validator.validate(doc(node)) }.doesNotThrowAnyException()
    }

    @Test
    fun `required parameter without binding nor default is rejected`() {
        val node = stencilNode(
            "s1",
            schema = mapOf(
                "properties" to mapOf("name" to mapOf("type" to "string")),
                "required" to listOf("name"),
            ),
            bindings = null,
        )
        assertThatThrownBy { validator.validate(doc(node)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("NODE_PARAMETER_BINDING_MISSING_REQUIRED")
            .hasMessageContaining("'name'")
    }

    @Test
    fun `required parameter without binding but with default is accepted`() {
        val node = stencilNode(
            "s1",
            schema = mapOf(
                "properties" to mapOf(
                    "name" to mapOf("type" to "string", "default" to "Anonymous"),
                ),
                "required" to listOf("name"),
            ),
            bindings = null,
        )
        assertThatCode { validator.validate(doc(node)) }.doesNotThrowAnyException()
    }

    @Test
    fun `multiple stencil nodes are all validated`() {
        // First node ok, second node has unknown binding → rejected.
        val ok = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("a" to mapOf("type" to "string"))),
            bindings = mapOf("a" to "customer.name"),
        )
        val bad = stencilNode(
            "s2",
            schema = mapOf("properties" to mapOf("a" to mapOf("type" to "string"))),
            bindings = mapOf("ghost" to "x"),
        )
        assertThatThrownBy { validator.validate(doc(ok, bad)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("NODE_PARAMETER_BINDING_UNKNOWN")
    }

    @Test
    fun `non-stencil node types are not inspected by this provider`() {
        // The provider registered only matches type=stencil. A 'text' node with
        // a parameterSchemaSnapshot on it (artificial) should be skipped.
        val text = Node(
            id = "t1",
            type = "text",
            props = mapOf(
                "parameterSchemaSnapshot" to mapOf(
                    "properties" to mapOf("a" to mapOf("type" to "string")),
                    "required" to listOf("a"),
                ),
            ),
        )
        assertThatCode { validator.validate(doc(text)) }.doesNotThrowAnyException()
    }

    @Test
    fun `field path in error message points at the offending binding`() {
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("a" to mapOf("type" to "string"))),
            bindings = mapOf("ghost" to "x"),
        )
        val ex = catchValidationException { validator.validate(doc(node)) }
        assertThat(ex.field).endsWith(".ghost")
    }

    @Test
    fun `syntactically valid jsonata binding is accepted`() {
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("name" to "customer.firstName & ' ' & customer.lastName"),
        )
        assertThatCode { validator.validate(doc(node)) }.doesNotThrowAnyException()
    }

    @Test
    fun `syntactically invalid jsonata binding is rejected`() {
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("name" to "recipient.firstName & '"),
        )
        assertThatThrownBy { validator.validate(doc(node)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("NODE_PARAMETER_BINDING_SYNTAX_INVALID")
            .hasMessageContaining("'name'")
    }

    @Test
    fun `jsonata syntax error includes the parser message`() {
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("name" to "recipient.firstName & '"),
        )
        try {
            validator.validate(doc(node))
            error("expected ValidationException")
        } catch (e: ValidationException) {
            val prefix = "NODE_PARAMETER_BINDING_SYNTAX_INVALID: parameter binding 'name' expression is invalid — "
            assertThat(e.message).startsWith(prefix)
            assertThat(e.message.length).isGreaterThan(prefix.length)
        }
    }

    @Test
    fun `field path in syntax error message points at the offending binding`() {
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("a" to mapOf("type" to "string"))),
            bindings = mapOf("a" to "broken('"),
        )
        val ex = catchValidationException { validator.validate(doc(node)) }
        assertThat(ex.field).endsWith(".a")
        assertThat(ex.message).contains("NODE_PARAMETER_BINDING_SYNTAX_INVALID")
    }

    // -- edge cases for non-string / empty binding values -------------------

    @Test
    fun `empty binding expression is rejected as invalid jsonata`() {
        // jsonata("") throws — empty expressions are not skipped
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("name" to ""),
        )
        assertThatThrownBy { validator.validate(doc(node)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("NODE_PARAMETER_BINDING_SYNTAX_INVALID")
            .hasMessageContaining("'name'")
    }

    @Test
    fun `blank binding expression is rejected as invalid jsonata`() {
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("name" to "   "),
        )
        assertThatThrownBy { validator.validate(doc(node)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("NODE_PARAMETER_BINDING_SYNTAX_INVALID")
            .hasMessageContaining("'name'")
    }

    @Test
    fun `non-string binding value is skipped by syntax check`() {
        // (rawValue as? String) returns null for non-strings → return@forEach
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("name" to 42),
        )
        assertThatCode { validator.validate(doc(node)) }.doesNotThrowAnyException()
    }

    @Test
    fun `null binding value is skipped by syntax check`() {
        // (rawValue as? String) returns null → return@forEach
        val node = stencilNode(
            "s1",
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            bindings = mapOf("name" to null),
        )
        assertThatCode { validator.validate(doc(node)) }.doesNotThrowAnyException()
    }

    @Test
    fun `empty binding on required parameter without default is caught by syntax check first`() {
        // jsonata("") throws SYNTAX_INVALID before the required-parameter check runs
        val node = stencilNode(
            "s1",
            schema = mapOf(
                "properties" to mapOf("name" to mapOf("type" to "string")),
                "required" to listOf("name"),
            ),
            bindings = mapOf("name" to ""),
        )
        assertThatThrownBy { validator.validate(doc(node)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("NODE_PARAMETER_BINDING_SYNTAX_INVALID")
            .hasMessageContaining("'name'")
    }
}
