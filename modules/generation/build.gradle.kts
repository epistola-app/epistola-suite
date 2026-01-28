plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":modules:template-model"))

    // iText 9 for PDF generation
    implementation("com.itextpdf:itext-core:9.1.0")

    // Kotlin reflection for expression evaluation
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JSONata expression language (Apache 2.0)
    implementation("com.dashjoin:jsonata:0.9.9")

    // GraalJS for JavaScript expression evaluation
    implementation("org.graalvm.polyglot:polyglot:24.1.1")
    implementation("org.graalvm.polyglot:js:24.1.1")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
