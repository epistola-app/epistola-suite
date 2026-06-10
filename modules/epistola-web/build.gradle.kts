plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // Shared web/UI toolkit for the host app and per-feature UI modules: the HTMX
    // functional-web DSL (htmx{}, page(), form{}), request extensions, and the
    // multi-fragment view resolver. Depends only on core (IDs + validation) so any
    // module that contributes UI can use it without depending on the app.
    api(project(":modules:epistola-core"))

    // Spring MVC functional routing (ServerRequest/ServerResponse, View/ViewResolver)
    // and Thymeleaf (SpringTemplateEngine for multi-fragment OOB rendering).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
