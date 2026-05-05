plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
    }
}

dependencies {
    // Business logic — provides mediator, queries, commands, security context
    implementation(project(":modules:epistola-core"))

    // Spring AI MCP server (streamable HTTP transport, served via Spring MVC)
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

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
