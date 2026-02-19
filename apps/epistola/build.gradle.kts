import org.cyclonedx.model.Component

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native") apply false
    id("org.cyclonedx.bom")
    id("org.jetbrains.kotlinx.kover")
}

// Conditionally apply native plugin only when building native images
val buildNativeImage = providers.gradleProperty("nativeImage").orNull?.toBoolean() ?: false
if (buildNativeImage) {
    apply(plugin = "org.graalvm.buildtools.native")
}

dependencies {
    // Core business logic module (includes template-model, generation transitively)
    implementation(project(":modules:epistola-core"))

    // Load test module (load testing functionality)
    implementation(project(":modules:loadtest"))

    // REST API module (controllers for external systems)
    implementation(project(":modules:rest-api"))

    // UI/Frontend modules
    implementation(project(":modules:editor"))

    // Spring Boot - UI layer concerns
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // HTMX for dynamic UI
    implementation(libs.htmx.spring.boot.thymeleaf)

    // Flyway for migrations (app deployment concern)
    implementation("org.flywaydb:flyway-database-postgresql")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Thymeleaf extras
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    // Jackson for Thymeleaf
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Development
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Runtime dependencies
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    // Annotation processors
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-grafana")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.playwright)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // JVM optimizations for faster test startup
    jvmArgs(
        "-XX:+UseParallelGC", // Parallel GC is faster for short-lived processes
        "-XX:TieredStopAtLevel=1", // Faster JVM startup (skip C2 compilation)
        "-Xms256m",
        "-Xmx512m",
    )

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false // Keep output clean
    }
}

// Enable BuildProperties bean by generating build-info.properties
springBoot {
    buildInfo()
}

// Configure CycloneDX SBOM generation for backend dependencies
tasks.cyclonedxDirectBom {
    projectType = Component.Type.APPLICATION
    includeBomSerialNumber = true
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("sbom/bom.json").get().asFile
}

// Copy SBOM to JAR resources for Docker embedding
val copySbomToResources by tasks.registering(Copy::class) {
    dependsOn(tasks.cyclonedxDirectBom)
    from(layout.buildDirectory.file("sbom/bom.json"))
    into(layout.buildDirectory.dir("resources/main/META-INF/sbom"))
}

// Copy design-system assets to static resources so Spring Boot serves them at /design-system/*
val copyDesignSystem by tasks.registering(Copy::class) {
    from(rootProject.file("modules/design-system"))
    include("*.css", "icons.svg")
    into(layout.buildDirectory.dir("resources/main/static/design-system"))
}

tasks.processResources {
    dependsOn(copySbomToResources, copyDesignSystem)
}

// Convenience task for generating SBOM standalone
tasks.register("generateSbom") {
    group = "verification"
    description = "Generate CycloneDX SBOM for backend dependencies"
    dependsOn(tasks.cyclonedxDirectBom)
}

// Docker image configuration
// Default: JVM image (recommended, stable)
// Use -PnativeImage=true to build a native image (currently broken due to JDBI/Kotlin reflection)
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    environment.set(
        mapOf(
            "BP_JVM_VERSION" to "25",
        ),
    )
}
