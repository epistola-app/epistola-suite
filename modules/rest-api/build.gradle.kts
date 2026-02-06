plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.dependency.management)
}

// Module containing REST API interfaces and models.
// Server interfaces are provided by the epistola-contract artifact.

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // Generated server interfaces from epistola-contract
    api("app.epistola.contract:server-kotlin-springboot4:1.0-SNAPSHOT")

    // Business logic - provides commands, queries, and domain services
    implementation(project(":modules:epistola-core"))

    // Spring Web for REST controllers
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // JSON serialization
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Kotlin reflection (required by Jackson)
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Template model (transitively from epistola-core, but explicit for generated code)
    implementation(project(":modules:template-model"))
}
