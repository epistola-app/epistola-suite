package app.epistola.suite.handlers

import app.epistola.suite.api.v1.ApiProblemTypes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import tools.jackson.databind.ObjectMapper

@Configuration
class ProblemDocsRoutes(private val objectMapper: ObjectMapper) {
    @Bean
    fun problemDocsRouteEndpoints(): RouterFunction<ServerResponse> = router {
        GET("/errors/{slug}") { request ->
            val slug = request.pathVariable("slug")
            val problemType = ApiProblemTypes.bySlug[slug]
                ?: return@GET ServerResponse.notFound().build()

            val example = mapOf(
                "type" to problemType.type.toString(),
                "title" to problemType.title,
                "status" to problemType.status.value(),
                "detail" to "Human-readable explanation for this occurrence",
                "instance" to "/api/...",
                "code" to problemType.code,
            )
            val exampleJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(example)

            ServerResponse.ok().render(
                "errors/problem-type",
                mapOf(
                    "problemType" to problemType,
                    "problemTypes" to ApiProblemTypes.all.sortedBy { it.slug },
                    "exampleJson" to exampleJson,
                ),
            )
        }
    }
}
