// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.catalog.validation.TemplateValidationCodes
import app.epistola.catalog.validation.TemplateValidationFinding
import app.epistola.catalog.validation.ValidationSeverity
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import app.epistola.catalog.validation.ParameterSchemaValidator as PortableParameterSchemaValidator

/**
 * Suite presentation adapter for portable parameter-schema validation.
 *
 * Suite accepts Jackson tree input at its command boundary. The tree is reduced
 * to the portable map representation and validated through the catalog contract;
 * the first deterministic finding is mapped back to Suite's existing exception.
 */
@Component
class ParameterSchemaValidator {
    private val objectMapper = ObjectMapper()

    fun validate(
        schema: JsonNode?,
        fieldPrefix: String = "parameterSchema",
    ) {
        if (schema == null || schema.isNull) return
        if (!schema.isObject) {
            throw ValidationException(
                fieldPrefix,
                "parameter schema must be a JSON object",
                ValidationCode.PARAMETER_SCHEMA_INVALID_TYPE,
            )
        }

        @Suppress("UNCHECKED_CAST")
        val portable = objectMapper.convertValue(schema, Map::class.java) as Map<String, Any?>
        val finding = PortableParameterSchemaValidator.validate(portable)
            .findings
            .filter { it.severity == ValidationSeverity.ERROR && it.code in PARAMETER_CODES }
            .minWithOrNull(compareBy({ PARAMETER_CODE_PRIORITY.indexOf(it.code) }, { it.path }))
        if (finding != null) throw finding.asSuiteException(fieldPrefix)
    }

    private fun TemplateValidationFinding.asSuiteException(fieldPrefix: String): ValidationException {
        val relative = path.removePrefix("$SCHEMA_PATH.")
        return ValidationException(
            field = if (relative == path) fieldPrefix else "$fieldPrefix.$relative",
            message = legacyMessage(relative),
            code = ValidationCode.entries.firstOrNull { it.wire == code } ?: ValidationCode.GENERIC,
        )
    }

    private fun TemplateValidationFinding.legacyMessage(relative: String): String = when {
        code == TemplateValidationCodes.PARAMETER_SCHEMA_INVALID_TYPE && relative == "required" ->
            "'required' must be an array"
        code == TemplateValidationCodes.PARAMETER_SCHEMA_INVALID_TYPE && relative == "properties" ->
            "'properties' must be an object"
        code == TemplateValidationCodes.PARAMETER_TYPE_UNSUPPORTED && message.contains("must contain primitives") ->
            "parameter '${relative.substringBefore(".items")}' is missing 'items'"
        code == TemplateValidationCodes.PARAMETER_TYPE_UNSUPPORTED && message.contains("'<missing>'") ->
            "parameter '${relative.substringBefore(".type")}' is missing 'type'"
        else -> message
    }

    companion object {
        private const val SCHEMA_PATH = "parameterSchema"
        private val PARAMETER_CODE_PRIORITY = listOf(
            TemplateValidationCodes.PARAMETER_SCHEMA_INVALID_TYPE,
            TemplateValidationCodes.PARAMETER_REQUIRED_UNKNOWN,
            TemplateValidationCodes.PARAMETER_NAME_INVALID,
            TemplateValidationCodes.PARAMETER_NAME_RESERVED,
            TemplateValidationCodes.PARAMETER_TYPE_UNSUPPORTED,
            TemplateValidationCodes.PARAMETER_DEFAULT_TYPE_MISMATCH,
        )
        private val PARAMETER_CODES = PARAMETER_CODE_PRIORITY.toSet()
    }
}
