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

dependencies {
    // Generated server interfaces from epistola-contract
    api(libs.epistola.server.restapi)

    // Business logic - provides commands, queries, and domain services
    implementation(project(":modules:epistola-core"))

    // Catalog module - provides import/export commands

    // Spring Web for REST controllers
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring Security (for exception types in ApiExceptionHandler)
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Micrometer (for API authentication metrics)
    implementation("io.micrometer:micrometer-core")

    // JSON serialization
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Kotlin reflection (required by Jackson)
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Template model (transitively from epistola-core, but explicit for generated code)
    implementation(libs.epistola.model)
}
