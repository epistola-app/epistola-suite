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
    implementation(project(":modules:vendor"))
    implementation(project(":modules:editor"))
    implementation(project(":modules:schema-manager"))
    implementation(project(":modules:template-model"))
    implementation(project(":modules:generation"))
    implementation(project(":modules:rest-api")) // OpenAPI spec and generated server interfaces

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(libs.htmx.spring.boot.thymeleaf)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)
    implementation(libs.jdbi.jackson3)
    implementation(libs.jdbi.spring)
    implementation(libs.json.schema.validator)
    implementation(libs.uuid.creator)
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("tools.jackson.module:jackson-module-kotlin")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // JVM optimizations for faster test startup
    jvmArgs(
        "-XX:+UseParallelGC", // Parallel GC is faster for short-lived processes
        "-XX:TieredStopAtLevel=1", // Faster JVM startup (skip C2 compilation)
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

tasks.processResources {
    dependsOn(copySbomToResources)
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
