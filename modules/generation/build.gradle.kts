plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":modules:template-model"))

    // iText 9 for PDF generation
    implementation("com.itextpdf:itext-core:9.1.0")

    // Kotlin reflection for expression evaluation
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
