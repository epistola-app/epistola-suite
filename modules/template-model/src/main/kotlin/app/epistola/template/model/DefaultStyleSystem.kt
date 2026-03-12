package app.epistola.template.model

import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

object DefaultStyleSystem {
    private const val RESOURCE_PATH = "style-system/default-style-system.json"

    private val objectMapper = jsonMapper {
        addModule(kotlinModule())
    }

    val data: StyleSystem by lazy {
        val stream = DefaultStyleSystem::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: error("Style system resource not found: $RESOURCE_PATH")
        stream.use { objectMapper.readValue<StyleSystem>(it) }
    }

    val canonicalPropertyKeys: Set<String> by lazy {
        data.canonicalProperties.mapTo(linkedSetOf()) { it.key }
    }

    val inheritablePropertyKeys: Set<String> by lazy {
        data.canonicalProperties
            .asSequence()
            .filter { it.inheritable }
            .map { it.key }
            .toCollection(linkedSetOf())
    }
}
