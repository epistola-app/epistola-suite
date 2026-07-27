// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.catalog.validation.TemplateDocumentKind
import app.epistola.catalog.validation.TemplateValidationCodes
import app.epistola.catalog.validation.TemplateValidationContext
import app.epistola.catalog.validation.TemplateValidationFinding
import app.epistola.catalog.validation.TemplateValidator
import app.epistola.catalog.validation.ValidationSeverity
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Suite presentation adapter for the portable catalog validator.
 *
 * The shared validator owns document semantics and deterministic aggregation.
 * Suite deliberately preserves its existing exception-oriented command surface:
 * the first portable error is mapped to [ValidationException], prefixed with the
 * request field that contains the document. Catalog resource resolution remains
 * unknown here because persistence/tenant lookup belongs to Suite call sites.
 * Suite also retains its product-capability gate for nested stencil authoring;
 * the portable catalog contract supports that composition for future consumers.
 */
@Component
class TemplateDocumentValidator(
    private val parameterSchemas: NodeParameterSchemaProviderRegistry,
) {
    fun validateTemplate(doc: TemplateDocument) {
        validateAt(TEMPLATE_FIELD, doc, TemplateDocumentKind.TEMPLATE)
    }

    fun validateStencil(doc: TemplateDocument) {
        rejectUnsupportedNestedStencilAuthoring(doc)
        validateAt(STENCIL_FIELD, doc, TemplateDocumentKind.STENCIL)
    }

    private fun rejectUnsupportedNestedStencilAuthoring(document: TemplateDocument) {
        val stencilNodeIds = document.nodes.values
            .filter { it.type == "stencil" }
            .map(Node::id)
            .sorted()
        if (stencilNodeIds.isNotEmpty()) {
            throw ValidationException(
                field = STENCIL_FIELD,
                message = "Stencil content cannot contain nested stencil components. " +
                    "Stencil nodes found: ${stencilNodeIds.joinToString(", ")}",
            )
        }
    }

    /**
     * Applies only traversal-safety checks to a template read from persistence.
     * Stored documents predate some semantic rules, but rendering must never
     * receive a malformed graph that can recurse or exhaust resources.
     */
    fun validateTemplateGraphForRendering(doc: TemplateDocument) {
        val finding = validate(doc, TemplateDocumentKind.TEMPLATE)
            .firstOrNull {
                it.code == TemplateValidationCodes.TEMPLATE_GRAPH_INVALID ||
                    it.code == TemplateValidationCodes.TEMPLATE_NODE_TYPE_UNSUPPORTED
            }
        if (finding != null) throw finding.asSuiteException(TEMPLATE_FIELD)
    }

    private fun validateAt(
        requestField: String,
        document: TemplateDocument,
        kind: TemplateDocumentKind,
    ) {
        validate(document, kind)
            .minWithOrNull(compareBy({ LEGACY_CODE_PRIORITY.indexOf(it.code).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }, { it.path }))
            ?.let { throw it.asSuiteException(requestField) }
    }

    private fun validate(
        document: TemplateDocument,
        kind: TemplateDocumentKind,
    ): List<TemplateValidationFinding> = TemplateValidator.validate(
        document,
        object : TemplateValidationContext {
            override val documentKind: TemplateDocumentKind = kind

            override fun resolveParameterSchema(
                node: Node,
                document: TemplateDocument,
            ): Map<String, Any?>? = parameterSchemas.resolve(node, document)
        },
    ).findings.filter { it.severity == ValidationSeverity.ERROR }

    private fun TemplateValidationFinding.asSuiteException(requestField: String): ValidationException = ValidationException(
        field = "$requestField.$path",
        message = message,
        code = ValidationCode.entries.firstOrNull { it.wire == code } ?: ValidationCode.GENERIC,
    )

    companion object {
        private const val TEMPLATE_FIELD = "templateModel"
        private const val STENCIL_FIELD = "content"
        private val LEGACY_CODE_PRIORITY = listOf(
            TemplateValidationCodes.TEMPLATE_GRAPH_INVALID,
            TemplateValidationCodes.TEMPLATE_NODE_TYPE_UNSUPPORTED,
            TemplateValidationCodes.PLACEHOLDER_NAME_DUPLICATE,
            TemplateValidationCodes.PLACEHOLDER_NAME_INVALID,
            TemplateValidationCodes.PLACEHOLDER_NESTED_DEFINITION,
            TemplateValidationCodes.PLACEHOLDER_OUTSIDE_STENCIL,
            TemplateValidationCodes.STENCIL_NESTING_DEPTH_EXCEEDED,
            TemplateValidationCodes.STENCIL_RECURSION,
            TemplateValidationCodes.NODE_PARAMETER_BINDINGS_INVALID_SHAPE,
            TemplateValidationCodes.NODE_PARAMETER_BINDING_NAME_INVALID,
            TemplateValidationCodes.NODE_PARAMETER_BINDING_EMPTY,
            TemplateValidationCodes.NODE_PARAMS_ALIAS_RESERVED,
            TemplateValidationCodes.NODE_PARAMETER_BINDING_SYNTAX_INVALID,
            TemplateValidationCodes.NODE_PARAMETER_BINDING_UNKNOWN,
            TemplateValidationCodes.NODE_PARAMETER_BINDING_MISSING_REQUIRED,
            TemplateValidationCodes.PAGEHEADER_TOO_MANY,
            TemplateValidationCodes.PAGEHEADER_ROOT_MISSING,
            TemplateValidationCodes.PAGEHEADER_NOT_AT_ROOT,
        )
    }
}
