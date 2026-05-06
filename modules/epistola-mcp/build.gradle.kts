plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

// Pin spring-ai versions explicitly: the spring-ai BOM does not propagate through
// Gradle module boundaries (consumers of this module would otherwise see an
// unresolved version when transitively pulling in the MCP starter), so we don't
// import the BOM and instead pin the coordinates we use.
val springAiVersion = libs.versions.spring.ai.get()

dependencies {
    // Business logic — provides mediator, queries, commands, security context
    implementation(project(":modules:epistola-core"))

    // Editor module ships dist/component-registry.json into
    // META-INF/resources/editor/ — read at runtime to power list_component_types.
    implementation(project(":modules:editor"))

    // Spring AI MCP server (streamable HTTP transport, served via Spring MVC)
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc:$springAiVersion")

    // Spring Web for the MVC dispatcher — required for the MCP HTTP endpoint
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Spring Security — only needed for principal/auth integration in tools
    implementation("org.springframework.boot:spring-boot-starter-security")

    // JSON serialization
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Kotlin reflection (required by Jackson + Spring AI tool schema generation)
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Template/document model types referenced by tool DTOs
    implementation(libs.epistola.model)

    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
