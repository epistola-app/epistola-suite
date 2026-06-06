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
    // Commercial support tier (hub client wiring, registration, credentials).
    api(project(":modules:epistola-support"))

    // Feedback domain — this module provides the production FeedbackSyncPort.
    api(project(":modules:feedback"))

    // Epistola Hub client (Kotlin gRPC SDK) — EpistolaHubClient + generated feedback
    // proto types. epistola-support depends on it too but only as `implementation`, so
    // it is not exposed transitively; declare it here as well.
    implementation("app.epistola.hub:client:0.2.0")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
