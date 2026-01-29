package app.epistola.suite.api.v1.shared

import app.epistola.suite.templates.model.TemplateModel
import tools.jackson.databind.ObjectMapper

internal object TemplateModelHelper {
    fun parseTemplateModel(objectMapper: ObjectMapper, map: Map<String, Any>): TemplateModel = objectMapper.convertValue(map, TemplateModel::class.java)
}
