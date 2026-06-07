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
    // The compatibility ("Upgrading") feature: reads the company-side compatibility-check results
    // for the tenant's catalogs (which the Backups feature uploads as snapshots) and shows them.
    // A separate feature from Backups; both rely on the snapshot sync but this one only reads.

    // Core (IDs, mediator, security, feature toggles).
    api(project(":modules:epistola-core"))

    // Commercial support tier (hub client wiring, registration, credentials). Inert until
    // epistola.support.enabled=true.
    api(project(":modules:epistola-support"))

    // Epistola Hub client (Kotlin gRPC SDK) — listCompatibilityResults.
    implementation(libs.epistola.hub.client)

    // Shared web/UI toolkit (HTMX functional-web DSL) for the Upgrading UI.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf).
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
