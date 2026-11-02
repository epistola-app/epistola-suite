rootProject.name = "epistola-suite"

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
        // TLC (:specs:tla) is published only as a GitHub release asset, not to Maven Central.
        exclusiveContent {
            forRepository {
                ivy("https://github.com/tlaplus/tlaplus/releases/download/") {
                    name = "TLA+ releases"
                    patternLayout { artifact("v[revision]/[artifact].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.lamport", "tla2tools") }
        }
        // Spring milestone repo for spring-ai 2.0-Mx (used by :modules:epistola-mcp)
        maven("https://repo.spring.io/milestone") {
            mavenContent {
                releasesOnly()
            }
        }
    }
}

include(":apps:epistola")
include(":apps:epistola-demo")
include(":apps:pdfrender")
include(":modules:epistola-core")
include(":modules:epistola-crypto")
include(":modules:epistola-web")
include(":modules:epistola-version-check")
include(":modules:epistola-quality")
include(":modules:epistola-audit")
include(":modules:loadtest")
include(":modules:editor")
include(":modules:rest-api")
include(":modules:generation")
include(":modules:guards")
include(":modules:testing")
include(":modules:epistola-mcp")
include(":modules:epistola-support")
include(":modules:epistola-support-feedback")
include(":modules:epistola-support-telemetry")
include(":specs:tla")
